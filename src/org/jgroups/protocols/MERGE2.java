// $Id: MERGE2.java,v 1.1 2008/09/03 04:24:39 commerce\wuti7102 Exp $

package org.jgroups.protocols;


import java.util.Properties;
import java.util.Vector;

import org.jgroups.*;
import org.jgroups.util.*;
import org.jgroups.stack.*;
import org.jgroups.log.Trace;




/**
 * Protocol to discover subgroups, e.g. existing due to a network partition (that healed). Example: group
 * {p,q,r,s,t,u,v,w} is split into 3 subgroups {p,q}, {r,s,t,u} and {v,w}. This protocol will eventually send
 * a MERGE event with the coordinators of each subgroup up the stack: {p,r,v}. Note that - depending on the time
 * of subgroup discovery - there could also be 2 MERGE events, which first join 2 of the subgroups, and then the
 * resulting group to the last subgroup. The real work of merging the subgroups into one larger group is done
 * somewhere above this protocol (typically in the GMS protocol).<p>
 * This protocol works as follows:
 * <ul>
 * <li>If coordinator: periodically retrieve the initial membership (using the FIND_INITIAL_MBRS event provided e.g.
 *     by PING or TCPPING protocols. This list contains {coord,addr} pairs.
 * <li>If there is more than 1 coordinator:
 *     <ol>
 *     <li>Get all coordinators
 *     <li>Create a MERGE event with the list of coordinators as argument
 *     <li>Send the event up the stack
 *     </ol>
 * </ul>
 *
 * <p>
 *
 * Requires: FIND_INITIAL_MBRS event from below<br>
 * Provides: sends MERGE event with list of coordinators up the stack<br>
 * @author Bela Ban, Oct 16 2001
 */
public class MERGE2 extends Protocol {
    Address local_addr=null;
    FindSubgroups task=null;             // task periodically executing as long as we are coordinator
    long min_interval=5000;     // minimum time between executions of the FindSubgroups task
    long max_interval=20000;    // maximum time between executions of the FindSubgroups task
    boolean is_coord=false;
    Promise find_promise=new Promise(); // to synchronize FindSubgroups.findInitialMembers() on


    public String getName() {
        return "MERGE2";
    }


    public boolean setProperties(Properties props) {
        String str;

        str=props.getProperty("min_interval");
        if(str != null) {
            min_interval=new Long(str).longValue();
            props.remove("min_interval");
        }

        str=props.getProperty("max_interval");
        if(str != null) {
            max_interval=new Long(str).longValue();
            props.remove("max_interval");
        }

        if(min_interval <= 0 || max_interval <= 0) {
            Trace.error("MERGE2.setProperties()", "min_interval and max_interval have to be > 0");
            return false;
        }
        if(max_interval <= min_interval) {
            Trace.error("MERGE2.setProperties()", "max_interval has to be greater than min_interval");
            return false;
        }

        if(props.size() > 0) {
            System.err.println("MERGE2.setProperties(): the following properties are not recognized:");
            props.list(System.out);
            return false;
        }
        return true;
    }


    public Vector requiredDownServices() {
        Vector retval=new Vector();
        retval.addElement(new Integer(Event.FIND_INITIAL_MBRS));
        return retval;
    }


    public void stop() {
        is_coord=false;
        stopTask();
    }


    /**
     * DON'T REMOVE ! This prevents the up-handler thread to be created, which is not needed in the protocol.
     */
    public void startUpHandler() {
        ;
    }


    /**
     * DON'T REMOVE ! This prevents the down-handler thread to be created, which is not needed in the protocol.
     */
    public void startDownHandler() {
        ;
    }


    public void up(Event evt) {
        switch(evt.getType()) {

            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address)evt.getArg();
                passUp(evt);
                break;

            case Event.FIND_INITIAL_MBRS_OK:
                find_promise.setResult(evt.getArg());
                passUp(evt); // could be needed by GMS
                break;

            default:
                passUp(evt);            // Pass up to the layer above us
                break;
        }
    }


    public void down(Event evt) {
        Vector mbrs=null;
        Address coord;

        switch(evt.getType()) {

            case Event.VIEW_CHANGE:
                passDown(evt);
                mbrs=((View)evt.getArg()).getMembers();
                if(mbrs == null || mbrs.size() == 0 || local_addr == null) {
                    stopTask();
                    break;
                }
                coord=(Address)mbrs.elementAt(0);
                if(coord.equals(local_addr)) {
                    is_coord=true;
                    startTask(); // start task if we became coordinator (doesn't start if already running)
                }
                else {
                    // if we were coordinator, but are no longer, stop task. this happens e.g. when we merge and someone
                    // else becomes the new coordinator of the merged group
                    if(is_coord) {
                        is_coord=false;
                    }
                    stopTask();
                }
                break;

            default:
                passDown(evt);          // Pass on to the layer below us
                break;
        }
    }


    /* -------------------------------------- Private Methods --------------------------------------- */
    void startTask() {
        if(task == null) {
            task=new FindSubgroups();
            task.start();
        }
    }

    void stopTask() {
        if(task != null) {
            task.stop(); // will cause timer to remove task from execution schedule
            task=null;
        }
    }
    /* ---------------------------------- End of Private Methods ------------------------------------ */




    /**
     * Task periodically executing (if role is coordinator). Gets the initial membership and determines
     * whether there are subgroups (multiple coordinators for the same group). If yes, it sends a MERGE event
     * with the list of the coordinators up the stack
     */
    private class FindSubgroups implements Runnable {
        Thread thread=null;


        public void start() {
            if(thread == null) {
                thread=new Thread(this, "MERGE2.FindSubgroups thread");
                thread.setDaemon(true);
                thread.start();
            }
        }


        public void stop() {
            if(thread != null && thread.isAlive()) {
                Thread tmp=thread;
                thread=null;
                tmp.interrupt(); // wakes up sleeping thread
                find_promise.reset();
            }
            thread=null;
        }


        public void run() {
            long interval;
            Vector coords=null;
            Event evt;
            Vector initial_mbrs;

            if(Trace.trace)
                Trace.info("MERGE2.FindSubgroups.run()", "merge task started");

            while(thread != null) {
                interval=computeInterval();
                Util.sleep(interval);
                if(thread == null) break;
                initial_mbrs=findInitialMembers();
                if(thread == null) break;
                if(Trace.trace) Trace.info("MERGE2.FindSubgroups.run()", "initial_mbrs=" + initial_mbrs);
                coords=findMultipleCoordinators(initial_mbrs);
                if(coords != null && coords.size() > 1) {
                    Trace.info("MERGE2.FindSubgroups.run()", "found multiple coordinators: " +
                                                             coords + "; sending up MERGE event");
                    evt=new Event(Event.MERGE, coords);
                    passUp(evt);
                }
            }
        }


        /**
         * Returns a random value within [min_interval - max_interval]
         */
        long computeInterval() {
            long retval=min_interval + Util.random(max_interval - min_interval);
            return retval;
        }


        /**
         * Returns a list of PingRsp pairs.
         */
        Vector findInitialMembers() {
            find_promise.reset();
            passDown(new Event(Event.FIND_INITIAL_MBRS));
            return (Vector)find_promise.getResult(0); // wait indefinitely until response is received
        }


        /**
         * Finds out if there is more than 1 coordinator in the initial_mbrs vector (contains PingRsp elements).
         * @param initial_mbrs A list of PingRsp pairs
         * @return Vector A list of the coordinators (Addresses) found. Will contain just 1 element for a correct
         *         membership, and more than 1 for multiple coordinators
         */
        Vector findMultipleCoordinators(Vector initial_mbrs) {
            Vector ret=new Vector();
            PingRsp rsp;
            Address coord;

            if(initial_mbrs == null) return null;
            for(int i=0; i < initial_mbrs.size(); i++) {
                rsp=(PingRsp)initial_mbrs.elementAt(i);
                coord=rsp.getCoordAddress();
                if(!ret.contains(coord))
                    ret.addElement(coord);
            }

            return ret;
        }

    }

}
