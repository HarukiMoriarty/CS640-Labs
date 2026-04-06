package edu.wisc.cs.sdn.vnet.rt;

import net.floodlightcontroller.packet.IPv4;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * An entry in a route table.
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class RouteEntry 
{
	/** Destination IP address */
	private int destinationAddress;
	
	/** Gateway IP address */
	private int gatewayAddress;
	
	/** Subnet mask */
	private int maskAddress;
	
	/** Router interface out which packets should be sent to reach
	 * the destination or gateway */
	private Iface iface;

	/** Hop count used by RIP to compare learned routes */
	private int metric;

	/** True if this route is for a directly connected subnet */
	private boolean directlyConnected;

	/** Time in milliseconds when this route was last refreshed */
	private long lastUpdated;
	
	/**
	 * Create a new route table entry.
	 * @param destinationAddress destination IP address
	 * @param gatewayAddress gateway IP address
	 * @param maskAddress subnet mask
	 * @param iface the router interface out which packets should 
	 *        be sent to reach the destination or gateway
	 */
	public RouteEntry(int destinationAddress, int gatewayAddress, 
			int maskAddress, Iface iface)
	{
		this(destinationAddress, gatewayAddress, maskAddress, iface, 1, false);
	}

	/**
	 * Create a new route table entry with RIP metadata.
	 * @param destinationAddress destination IP address
	 * @param gatewayAddress gateway IP address
	 * @param maskAddress subnet mask
	 * @param iface the router interface out which packets should 
	 *        be sent to reach the destination or gateway
	 * @param metric hop count used for RIP advertisements
	 * @param directlyConnected true if the route is attached to a local iface
	 */
	public RouteEntry(int destinationAddress, int gatewayAddress, 
			int maskAddress, Iface iface, int metric, boolean directlyConnected)
	{
		this.destinationAddress = destinationAddress;
		this.gatewayAddress = gatewayAddress;
		this.maskAddress = maskAddress;
		this.iface = iface;
		this.metric = metric;
		this.directlyConnected = directlyConnected;
		this.touch();
	}
	
	/**
	 * @return destination IP address
	 */
	public int getDestinationAddress()
	{ return this.destinationAddress; }
	
	/**
	 * @return gateway IP address
	 */
	public int getGatewayAddress()
	{ return this.gatewayAddress; }

	public void setGatewayAddress(int gatewayAddress)
	{ this.gatewayAddress = gatewayAddress; }
	
	/**
	 * @return subnet mask 
	 */
	public int getMaskAddress()
	{ return this.maskAddress; }
	
	/**
	 * @return the router interface out which packets should be sent to 
	 *         reach the destination or gateway
	 */
	public Iface getInterface()
	{ return this.iface; }

	public void setInterface(Iface iface)
	{ this.iface = iface; }

	/**
	 * @return RIP metric for this route
	 */
	public int getMetric()
	{ return this.metric; }

	public void setMetric(int metric)
	{ this.metric = metric; }

	/**
	 * @return true if this route is directly connected
	 */
	public boolean isDirectlyConnected()
	{ return this.directlyConnected; }

	public void setDirectlyConnected(boolean directlyConnected)
	{ this.directlyConnected = directlyConnected; }

	/**
	 * Record that the route was refreshed by a recent update.
	 */
	public void touch()
	{ this.lastUpdated = System.currentTimeMillis(); }

	/**
	 * @return time in milliseconds when this route was last updated
	 */
	public long getLastUpdated()
	{ return this.lastUpdated; }
	
	public String toString()
	{
		return String.format("%s \t%s \t%s \t%s \t%d \t%s",
				IPv4.fromIPv4Address(this.destinationAddress),
				IPv4.fromIPv4Address(this.gatewayAddress),
				IPv4.fromIPv4Address(this.maskAddress),
				this.iface.getName(),
				this.metric,
				this.directlyConnected ? "C" : "R");
	}
}
