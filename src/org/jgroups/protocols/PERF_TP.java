// $Id: PERF_TP.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $

package org.jgroups.protocols;


import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Message;
import org.jgroups.log.Trace;
import org.jgroups.stack.Protocol;

import java.util.Properties;


/**
 * Measures the time for a message to travel from the channel to the transport
 * @author Bela Ban
 * @version $Id: PERF_TP.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $
 */
public class PERF_TP extends Protocol {
    private Address local_addr=null;
    static  PERF_TP instance=null;
    long    stop, start;
    long    num_msgs=0;
    long    expected_msgs=0;
    boolean done=false;


    public static PERF_TP getInstance() {
        return instance;
    }

    public PERF_TP() {
        if(instance == null)
            instance=this;
    }


    public String toString() {
        return "Protocol PERF_TP (local address: " + local_addr + ")";
    }


    public boolean done() {
        return done;
    }

    public long getNumMessages() {
        return num_msgs;
    }

    public void setExpectedMessages(long m) {
        expected_msgs=m;
        num_msgs=0;
        done=false;
        start=System.currentTimeMillis();
    }

    public void reset() {
        num_msgs=expected_msgs=stop=start=0;
        done=false;
    }

    public long getTotalTime() {
        return stop-start;
    }


    /*------------------------------ Protocol interface ------------------------------ */

    public String getName() {
        return "PERF_TP";
    }


    /** Setup the Protocol instance acording to the configuration string */
    public boolean setProperties(Properties props) {

        if(props.size() > 0) {
            System.err.println("PERF_TP.setProperties(): the following properties are not recognized:");
            props.list(System.out);
            return false;
        }
        return true;
    }

    public void init() throws Exception {
        local_addr=new org.jgroups.stack.IpAddress("localhost", 10000); // fake address
    }

    public void start() throws Exception {
        passUp(new Event(Event.SET_LOCAL_ADDRESS, local_addr));
    }


    /**
     * Caller by the layer above this layer. Usually we just put this Message
     * into the send queue and let one or more worker threads handle it. A worker thread
     * then removes the Message from the send queue, performs a conversion and adds the
     * modified Message to the send queue of the layer below it, by calling Down).
     */
    public void down(Event evt) {
        Message msg;
        Address dest_addr;


        switch(evt.getType()) {

            case Event.MSG:
                if(done) {
                    Trace.warn("PERF_TP.down()", "all done (discarding msg)");
                    break;
                }
                msg=(Message)evt.getArg();
                dest_addr=msg.getDest();
                if(dest_addr == null)
                    num_msgs++;
                if(num_msgs >= expected_msgs) {
                    stop=System.currentTimeMillis();
                    synchronized(this) {
                        done=true;
                        this.notifyAll();
                    }
                    Trace.info("PERF_TP.down()", "all done (num_msgs=" + num_msgs + ", expected_msgs=" + expected_msgs);
                }
                break;

            case Event.CONNECT:
                passUp(new Event(Event.CONNECT_OK));
                break;

            case Event.DISCONNECT:
                passUp(new Event(Event.DISCONNECT_OK));
                break;
        }


    }



    /*--------------------------- End of Protocol interface -------------------------- */




}
