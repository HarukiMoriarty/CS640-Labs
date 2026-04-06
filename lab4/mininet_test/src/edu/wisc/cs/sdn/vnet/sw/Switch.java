package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{
	/** Timeout for MAC table entries in milliseconds (15 seconds) */
	private static final long TIMEOUT = 15000;

	/** MAC address table: maps MAC address to the interface and timestamp */
	private Map<Long, MacTableEntry> macTable;

	/**
	 * An entry in the MAC address table.
	 */
	private class MacTableEntry
	{
		Iface iface;
		long timestamp;

		MacTableEntry(Iface iface, long timestamp)
		{
			this.iface = iface;
			this.timestamp = timestamp;
		}
	}

	/**
	 * Creates a switch for a specific host.
	 * @param host hostname for the switch
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host, logfile);
		this.macTable = new ConcurrentHashMap<Long, MacTableEntry>();
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
		/* Learn: record source MAC and incoming interface                   */
		MACAddress srcMAC = etherPacket.getSourceMAC();
		long srcKey = srcMAC.toLong();
		macTable.put(srcKey, new MacTableEntry(inIface, System.currentTimeMillis()));

		/* Lookup: check if destination MAC is in the table                  */
		MACAddress dstMAC = etherPacket.getDestinationMAC();
		long dstKey = dstMAC.toLong();
		MacTableEntry entry = macTable.get(dstKey);

		if (entry != null
				&& (System.currentTimeMillis() - entry.timestamp) < TIMEOUT)
		{
			/* Forward to the known interface                               */
			sendPacket(etherPacket, entry.iface);
		}
		else
		{
			/* Remove stale entry if it existed */
			if (entry != null)
			{ macTable.remove(dstKey); }

			/* Flood: send out all interfaces except the one it arrived on  */
			for (Iface iface : this.interfaces.values())
			{
				if (!iface.getName().equals(inIface.getName()))
				{ sendPacket(etherPacket, iface); }
			}
		}
		/********************************************************************/
	}
}
