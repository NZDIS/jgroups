// $Id: HDRS.java,v 1.1 2008/09/03 04:24:38 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.stack.*;


/**
 * Example of a protocol layer. Contains no real functionality, can be used as a template.
 */
public class HDRS extends Protocol {
    public String  getName() {return "HDRS";}


    private void printMessage(Message msg, String label) {
	System.out.println("------------------------- " + label + " ----------------------");
	System.out.println(msg);
	msg.printObjectHeaders();
	System.out.println("--------------------------------------------------------------");
    }


    public void up(Event evt) {
 	if(evt.getType() == Event.MSG) {
 	    Message msg=(Message)evt.getArg();
 	    printMessage(msg, "up");
 	}
	passUp(evt); // Pass up to the layer above us
    }



    public void down(Event evt) {
 	if(evt.getType() == Event.MSG) {
 	    Message msg=(Message)evt.getArg();
 	    printMessage(msg, "down");
	}

	passDown(evt);  // Pass on to the layer below us
    }


}
