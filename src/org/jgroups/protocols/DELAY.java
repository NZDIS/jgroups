// $Id: DELAY.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import java.util.Properties;
import org.jgroups.*;
import org.jgroups.stack.*;
import org.jgroups.log.Trace;
import org.jgroups.util.*;




/**
   Delays incoming/outgoing messages by a random number of milliseconds (range between 0 and n 
   where n is determined by the user). Incoming messages can be delayed independently from
   outgoing messages (or not delayed at all).<p>
   This protocol should be inserted directly above the bottommost protocol (e.g. UDP).
 */

public class DELAY extends Protocol {
    int  in_delay=0, out_delay=0;

    /** All protocol names have to be unique ! */
    public String  getName() {return "DELAY";}


    public boolean setProperties(Properties props) {
	String     str;

	str=props.getProperty("in_delay");
	if(str != null) {
	    in_delay=new Integer(str).intValue();
	    props.remove("in_delay");
	}

	str=props.getProperty("out_delay");
	if(str != null) {
	    out_delay=new Integer(str).intValue();
	    props.remove("out_delay");
	}

	if(props.size() > 0) {
	    System.err.println("DELAY.setProperties(): these properties are not recognized:");
	    props.list(System.out);
	    return false;
	}
	return true;
    }




    public void up(Event evt) {
	int delay=in_delay > 0 ? computeDelay(in_delay) : 0;


	switch(evt.getType()) {
	case Event.MSG:         // Only delay messages, not other events !
	    if(Trace.trace)
		Trace.info("DELAY.up()", "delaying incoming message for " + delay + " milliseconds");
	    Util.sleep(delay);	    
	    break;
	}
	
	passUp(evt);            // Pass up to the layer above us
    }





    public void down(Event evt) {
	int delay=out_delay > 0 ? computeDelay(out_delay) : 0;

	switch(evt.getType()) {

	case Event.MSG:         // Only delay messages, not other events !
	    if(Trace.trace)
		Trace.info("DELAY.down()", "delaying outgoing message for " + delay + " milliseconds");
	    Util.sleep(delay);
	    break;
	}

	passDown(evt);          // Pass on to the layer below us
    }

    
    /** Compute a random number between 0 and n */
    int computeDelay(int n) {
	return (int)((Math.random() * 1000000) % n);
    }


}
