package edu.wisc.cs.sdn.vnet.rt;

import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	private static final int RIP_MULTICAST_IP =
			IPv4.toIPv4Address("224.0.0.9");
	private static final String RIP_BROADCAST_MAC = "FF:FF:FF:FF:FF:FF";
	private static final int RIP_INFINITY = 16;
	private static final int DIRECT_ROUTE_METRIC = 1;
	private static final long RIP_RESPONSE_INTERVAL_MS = 10 * 1000;
	private static final long RIP_ROUTE_TIMEOUT_MS = 30 * 1000;
	private static final long RIP_ROUTE_CLEANUP_INTERVAL_MS = 1000;

	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;

	/** Random number generator for simulating packet drops */
	private Random dropRandom = new Random();

	/** True when the router should build its routes via RIP */
	private boolean ripEnabled;

	/** Periodic RIP tasks run from a daemon timer */
	private Timer ripTimer;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.ripEnabled = false;
		this.ripTimer = null;
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		this.ripEnabled = false;
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Start RIP and seed the route table with directly connected subnets.
	 */
	public void startRip()
	{
		if (this.ripEnabled)
		{ return; }

		this.ripEnabled = true;
		this.initializeDirectRoutes();
		this.startRipTasks();

		System.out.println("Initialized RIP route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");

		for (Iface iface : this.interfaces.values())
		{ this.sendRipRequest(iface); }
	}

	@Override
	public void destroy()
	{
		if (this.ripTimer != null)
		{ this.ripTimer.cancel(); }
		super.destroy();
	}

	/**
	 * Populate the route table with the subnets attached to this router.
	 */
	private void initializeDirectRoutes()
	{
		for (Iface iface : this.interfaces.values())
		{
			int subnet = this.getSubnetAddress(iface.getIpAddress(),
					iface.getSubnetMask());
			RouteEntry existing = this.routeTable.find(subnet, iface.getSubnetMask());
			if (existing == null)
			{
				this.routeTable.insert(subnet, 0, iface.getSubnetMask(), iface,
						DIRECT_ROUTE_METRIC, true);
			}
			else
			{
				this.routeTable.update(subnet, iface.getSubnetMask(), 0, iface,
						DIRECT_ROUTE_METRIC, true);
			}
		}
	}

	/**
	 * Schedule the periodic unsolicited responses and route aging tasks.
	 */
	private void startRipTasks()
	{
		this.ripTimer = new Timer(true);

		this.ripTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				try
				{ sendRipResponseOnAllInterfaces(); }
				catch (RuntimeException e)
				{ e.printStackTrace(); }
			}
		}, RIP_RESPONSE_INTERVAL_MS, RIP_RESPONSE_INTERVAL_MS);

		this.ripTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				try
				{ expireStaleRoutes(); }
				catch (RuntimeException e)
				{ e.printStackTrace(); }
			}
		}, RIP_ROUTE_CLEANUP_INTERVAL_MS, RIP_ROUTE_CLEANUP_INTERVAL_MS);
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		/* Only handle IPv4 packets; drop all others                        */
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }

		/* Get the IPv4 header                                              */
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();

		/* Verify checksum                                                  */
		short origChecksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte[] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		short computedChecksum = ipPacket.getChecksum();
		if (origChecksum != computedChecksum)
		{ return; }

		/* RIP packets are handled by the router control plane and should
		 * not fall through to normal forwarding. */
		if (this.isRipPacket(ipPacket))
		{
			this.handleRipPacket(etherPacket, ipPacket, inIface);
			return;
		}

		/* Check if packet is destined for one of the router's interfaces   */
		int dstIp = ipPacket.getDestinationAddress();
		for (Iface iface : this.interfaces.values())
		{
			if (iface.getIpAddress() == dstIp)
			{ return; }
		}

		/* Decrement TTL and drop if TTL reaches 0                          */
		byte ttl = ipPacket.getTtl();
		ttl--;
		if (ttl == 0)
		{ return; }
		ipPacket.setTtl(ttl);

		/* Recompute checksum after TTL change                              */
		ipPacket.resetChecksum();

		/* Look up route in routing table using longest prefix match         */
		RouteEntry bestRoute = this.routeTable.lookup(dstIp);
		if (bestRoute == null)
		{ return; }

		/* Determine next-hop IP address                                    */
		int nextHopIp = bestRoute.getGatewayAddress();
		if (nextHopIp == 0)
		{ nextHopIp = dstIp; }

		/* Look up MAC address for next-hop IP in ARP cache                 */
		ArpEntry arpEntry = this.arpCache.lookup(nextHopIp);
		if (arpEntry == null)
		{ return; }

		/* Set Ethernet header: source MAC = outgoing iface, dest MAC = next hop */
		Iface outIface = bestRoute.getInterface();
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());

		/* Drop ~5% of packets to simulate lossy network */
		if (dropRandom.nextInt(100) < 5)
		{
			System.out.println("*** Dropping packet (simulated loss)");
			return;
		}

		/* Send the packet out the appropriate interface                     */
		this.sendPacket(etherPacket, outIface);
		/********************************************************************/
	}

	/**
	 * @return true if the packet contains RIP carried over UDP/520
	 */
	private boolean isRipPacket(IPv4 ipPacket)
	{
		if (ipPacket.getProtocol() != IPv4.PROTOCOL_UDP)
		{ return false; }

		if (!(ipPacket.getPayload() instanceof UDP))
		{ return false; }

		UDP udpPacket = (UDP) ipPacket.getPayload();
		return (udpPacket.getDestinationPort() == UDP.RIP_PORT);
	}

	/**
	 * Handle RIP requests and responses.
	 */
	private void handleRipPacket(Ethernet etherPacket, IPv4 ipPacket, Iface inIface)
	{
		if (!this.ripEnabled)
		{ return; }

		UDP udpPacket = (UDP) ipPacket.getPayload();
		if (!(udpPacket.getPayload() instanceof RIPv2))
		{ return; }

		RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();
		if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST)
		{
			this.sendRipResponse(inIface, ipPacket.getSourceAddress(),
					etherPacket.getSourceMACAddress());
		}
		else if (ripPacket.getCommand() == RIPv2.COMMAND_RESPONSE)
		{
			this.processRipResponse(ripPacket, ipPacket.getSourceAddress(), inIface);
		}
	}

	/**
	 * Update the forwarding table with routes learned from one neighbor.
	 */
	private void processRipResponse(RIPv2 ripPacket, int neighborIp, Iface inIface)
	{
		for (RIPv2Entry ripEntry : ripPacket.getEntries())
		{
			int mask = ripEntry.getSubnetMask();
			int destination = this.getSubnetAddress(ripEntry.getAddress(), mask);
			int metric = Math.min(RIP_INFINITY, ripEntry.getMetric() + 1);
			RouteEntry existing = this.routeTable.find(destination, mask);

			if ((existing != null) && existing.isDirectlyConnected())
			{ continue; }

			if (metric >= RIP_INFINITY)
			{
				if ((existing != null)
						&& (existing.getGatewayAddress() == neighborIp)
						&& (existing.getInterface() == inIface))
				{
					this.routeTable.remove(destination, mask);
					System.out.println("RIP removed route "
							+ IPv4.fromIPv4Address(destination) + "/"
							+ IPv4.fromIPv4Address(mask));
				}
				continue;
			}

			if (existing == null)
			{
				this.routeTable.insert(destination, neighborIp, mask, inIface,
						metric, false);
				System.out.println("RIP learned route "
						+ IPv4.fromIPv4Address(destination) + " via "
						+ IPv4.fromIPv4Address(neighborIp));
				continue;
			}

			if ((existing.getGatewayAddress() == neighborIp)
					&& (existing.getInterface() == inIface))
			{
				this.routeTable.update(destination, mask, neighborIp, inIface,
						metric, false);
				continue;
			}

			if (metric < existing.getMetric())
			{
				this.routeTable.update(destination, mask, neighborIp, inIface,
						metric, false);
				System.out.println("RIP improved route "
						+ IPv4.fromIPv4Address(destination) + " via "
						+ IPv4.fromIPv4Address(neighborIp));
			}
		}
	}

	/**
	 * Send a RIP request out one interface to discover neighbors.
	 */
	private void sendRipRequest(Iface outIface)
	{
		RIPv2 ripPacket = new RIPv2();
		ripPacket.setCommand(RIPv2.COMMAND_REQUEST);

		// A single all-zero entry with metric 16 asks the neighbor for
		// its full table, which is enough for this assignment.
		RIPv2Entry requestEntry = new RIPv2Entry();
		requestEntry.setAddressFamily((short)0);
		requestEntry.setMetric(RIP_INFINITY);
		ripPacket.addEntry(requestEntry);

		this.sendRipPacket(outIface, RIP_MULTICAST_IP,
				Ethernet.toMACAddress(RIP_BROADCAST_MAC), ripPacket);
	}

	/**
	 * Send the router's current table to all interfaces using RIP multicast.
	 */
	private void sendRipResponseOnAllInterfaces()
	{
		if (!this.ripEnabled)
		{ return; }

		for (Iface iface : this.interfaces.values())
		{
			this.sendRipResponse(iface, RIP_MULTICAST_IP,
					Ethernet.toMACAddress(RIP_BROADCAST_MAC));
		}
	}

	/**
	 * Send the current route table as a RIP response.
	 */
	private void sendRipResponse(Iface outIface, int destinationIp,
			byte[] destinationMac)
	{
		RIPv2 ripPacket = new RIPv2();
		ripPacket.setCommand(RIPv2.COMMAND_RESPONSE);

		List<RouteEntry> entries = this.routeTable.getEntries();
		for (RouteEntry entry : entries)
		{
			int advertisedMetric = Math.min(RIP_INFINITY, entry.getMetric());

			// Poison reverse keeps us from advertising a route back out the
			// same interface from which it was learned.
			if (!entry.isDirectlyConnected() && (entry.getInterface() == outIface))
			{ advertisedMetric = RIP_INFINITY; }

			RIPv2Entry ripEntry = new RIPv2Entry(entry.getDestinationAddress(),
					entry.getMaskAddress(), advertisedMetric);
			ripPacket.addEntry(ripEntry);
		}

		this.sendRipPacket(outIface, destinationIp, destinationMac, ripPacket);
	}

	/**
	 * Build and transmit a RIP packet over Ethernet/IPv4/UDP.
	 */
	private void sendRipPacket(Iface outIface, int destinationIp,
			byte[] destinationMac, RIPv2 ripPacket)
	{
		Ethernet etherPacket = new Ethernet();
		etherPacket.setEtherType(Ethernet.TYPE_IPv4);
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		etherPacket.setDestinationMACAddress(destinationMac);

		IPv4 ipPacket = new IPv4();
		ipPacket.setTtl((byte)64);
		ipPacket.setSourceAddress(outIface.getIpAddress());
		ipPacket.setDestinationAddress(destinationIp);

		UDP udpPacket = new UDP();
		udpPacket.setSourcePort(UDP.RIP_PORT);
		udpPacket.setDestinationPort(UDP.RIP_PORT);
		udpPacket.setPayload(ripPacket);

		ipPacket.setPayload(udpPacket);
		etherPacket.setPayload(ipPacket);
		this.sendPacket(etherPacket, outIface);
	}

	/**
	 * Remove learned RIP routes that have not been refreshed recently.
	 */
	private void expireStaleRoutes()
	{
		long now = System.currentTimeMillis();
		List<RouteEntry> entries = this.routeTable.getEntries();
		for (RouteEntry entry : entries)
		{
			if (entry.isDirectlyConnected())
			{ continue; }

			if ((now - entry.getLastUpdated()) > RIP_ROUTE_TIMEOUT_MS)
			{
				this.routeTable.remove(entry.getDestinationAddress(),
						entry.getMaskAddress());
				System.out.println("RIP expired route "
						+ IPv4.fromIPv4Address(entry.getDestinationAddress())
						+ " via "
						+ IPv4.fromIPv4Address(entry.getGatewayAddress()));
			}
		}
	}

	/**
	 * Compute the subnet address for an IP/mask pair.
	 */
	private int getSubnetAddress(int ipAddress, int subnetMask)
	{
		return (ipAddress & subnetMask);
	}
}
