// $Id: PingRsp.java,v 1.1 2008/09/03 04:24:39 commerce\wuti7102 Exp $

package org.jgroups.protocols;
import org.jgroups.*;


public class PingRsp implements java.io.Serializable {
    public Address own_addr=null;
    public Address coord_addr=null;
    
    public PingRsp(Address own_addr, Address coord_addr) {
	this.own_addr=own_addr; this.coord_addr=coord_addr;
    }

    public boolean isCoord() {
	if(own_addr != null && coord_addr != null)
	    return own_addr.equals(coord_addr);
	return false;
    }

    public Address getAddress()      {return own_addr;}
    public Address getCoordAddress() {return coord_addr;}

    public String toString() {
	return "[own_addr=" + own_addr + ", coord_addr=" + coord_addr + "]";
    }
}
