// $Id: TRACE.java,v 1.1 2008/09/03 04:24:39 commerce\wuti7102 Exp $

package org.jgroups.protocols;
import org.jgroups.*;
import org.jgroups.stack.*;



public class TRACE extends Protocol {

    public TRACE() {}

    public String        getName()             {return "TRACE";}

    

    public void up(Event evt) {
	System.out.println("---------------- TRACE (received) ----------------------");
	System.out.println(evt);
	System.out.println("--------------------------------------------------------");
	passUp(evt);
    }


    public void down(Event evt) {
	System.out.println("------------------- TRACE (sent) -----------------------");
	System.out.println(evt);
	System.out.println("--------------------------------------------------------");
	passDown(evt);
    }


    public String toString() {
	return "Protocol TRACE";
    }


}
