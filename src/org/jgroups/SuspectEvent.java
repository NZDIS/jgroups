// $Id: SuspectEvent.java,v 1.1 2008/09/03 04:24:42 commerce\wuti7102 Exp $

package org.jgroups;

public class SuspectEvent {
    Object suspected_mbr;

    public SuspectEvent(Object suspected_mbr) {this.suspected_mbr=suspected_mbr;}

    public Object getMember() {return suspected_mbr;}
    public String toString() {return "SuspectEvent";}
}
