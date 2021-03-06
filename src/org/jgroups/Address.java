// $Id: Address.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups;

import java.io.Externalizable;



/**
 * Abstract address. Used to identify members on a group to send messages to.
 * Addresses are mostly generated by the bottom-most (transport) layers (e.g. UDP, TCP, LOOPBACK).
 * Subclasses need to implement the following methods:
 * <ul>
 * <li>isMultiCastAddress()
 * <li>equals()
 * <li>hashCode()
 * <li>compareTo()
 * </ol>
 * @author Bela Ban
 */
public interface Address extends Externalizable, Comparable, Cloneable {

    /**
     * Checks whether this is an address that represents multiple destinations,
     * e.g. a class D address in the internet
     * @return true if this is a multicast address, false if it is a unicast address
     */
    boolean  isMulticastAddress();
}
