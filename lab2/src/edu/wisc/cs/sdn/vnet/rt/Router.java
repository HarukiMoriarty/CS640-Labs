package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
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

		/* Decrement TTL and drop if TTL reaches 0                          */
		byte ttl = ipPacket.getTtl();
		ttl--;
		if (ttl == 0)
		{ return; }
		ipPacket.setTtl(ttl);

		/* Recompute checksum after TTL change                              */
		ipPacket.resetChecksum();

		/* Check if packet is destined for one of the router's interfaces   */
		int dstIp = ipPacket.getDestinationAddress();
		for (Iface iface : this.interfaces.values())
		{
			if (iface.getIpAddress() == dstIp)
			{ return; }
		}

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

		/* Send the packet out the appropriate interface                     */
		this.sendPacket(etherPacket, outIface);
		/********************************************************************/
	}
}
