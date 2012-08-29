// $Id: FD_PROB.java,v 1.1 2008/09/03 04:24:39 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Vector;

import org.jgroups.*;
import org.jgroups.util.*;
import org.jgroups.stack.*;
import org.jgroups.log.Trace;


/**
 * Probabilistic failure detection protocol based on "A Gossip-Style Failure Detection Service"
 * by Renesse, Minsky and Hayden.<p>
 * Each member maintains a list of all other members: for each member P, 2 data are maintained, a heartbeat
 * counter and the time of the last increment of the counter. Each member periodically sends its own heartbeat
 * counter list to a randomly chosen member Q. Q updates its own heartbeat counter list and the associated
 * time (if counter was incremented). Each member periodically increments its own counter. If, when sending
 * its heartbeat counter list, a member P detects that another member Q's heartbeat counter was not incremented
 * for timeout seconds, Q will be suspected.<p>
 * This protocol can be used both with a PBCAST *and* regular stacks.
 * @author Bela Ban 1999
 * @version $Revision: 1.1 $
 */
public class FD_PROB extends Protocol implements Runnable {
    Address local_addr=null;
    Thread hb=null;
    long timeout=3000;  // before a member with a non updated timestamp is suspected
    long gossip_interval=1000;
    Vector members=null;
    Hashtable counters=new Hashtable();        // keys=Addresses, vals=FdEntries
    Hashtable invalid_pingers=new Hashtable(); // keys=Address, vals=Integer (number of pings from suspected mbrs)
    int max_tries=2;   // number of times to send a are-you-alive msg (tot time= max_tries*timeout)


    public String getName() {
        return "FD_PROB";
    }


    public boolean setProperties(Properties props) {
        String str;

        str=props.getProperty("timeout");
        if(str != null) {
            timeout=new Long(str).longValue();
            props.remove("timeout");
        }

        str=props.getProperty("gossip_interval");
        if(str != null) {
            gossip_interval=new Long(str).longValue();
            props.remove("gossip_interval");
        }

        str=props.getProperty("max_tries");
        if(str != null) {
            max_tries=Integer.parseInt(str);
            props.remove("max_tries");
        }

        if(props.size() > 0) {
            System.err.println("FD_PROB.setProperties(): the following properties are not recognized:");
            props.list(System.out);
            return false;
        }
        return true;
    }


    public void start() throws Exception {
        if(hb == null) {
            hb=new Thread(this, "FD_PROB.HeartbeatThread");
            hb.setDaemon(true);
            hb.start();
        }
    }


    public void stop() {
        Thread tmp=null;
        if(hb != null && hb.isAlive()) {
            tmp=hb;
            hb=null;
            tmp.interrupt();
            try {
                tmp.join(timeout);
            }
            catch(Exception ex) {
            }
        }
        hb=null;
    }


    public void up(Event evt) {
        Message msg;
        Address hb_sender;
        FdHeader hdr=null;
        Object obj;

        switch(evt.getType()) {

            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address) evt.getArg();
                break;

            case Event.MSG:
                msg=(Message) evt.getArg();
                obj=msg.getHeader(getName());
                if(obj == null || !(obj instanceof FdHeader)) {
                    updateCounter(msg.getSrc());  // got a msg from this guy, reset its time (we heard from it now)
                    break;
                }

                hdr=(FdHeader) msg.removeHeader(getName());
                switch(hdr.type) {
                    case FdHeader.HEARTBEAT:                           // heartbeat request; send heartbeat ack
                        if(checkPingerValidity(msg.getSrc()) == false) // false == sender of heartbeat is not a member
                            return;

                        // 2. Update my own array of counters
                        if(Trace.trace)
                            Trace.info("FD_PROB.updateCounters()", "<-- HEARTBEAT from " + msg.getSrc());
                        updateCounters(hdr);
                        return;                                     // don't pass up !
                    case FdHeader.NOT_MEMBER:
                        Trace.warn("FD_PROB.up()", "NOT_MEMBER: I'm being shunned; exiting");
                        passUp(new Event(Event.EXIT));
                        return;
                    default:
                        Trace.warn("FD_PROB.up()", "FdHeader type " + hdr.type + " not known");
                        return;
                }
        }
        passUp(evt);                                        // pass up to the layer above us
    }


    public void down(Event evt) {
        Message msg;
        int num_mbrs;
        Vector excluded_mbrs;
        FdEntry entry;
        Address mbr;

        switch(evt.getType()) {

            // Start heartbeat thread when we have more than 1 member; stop it when membership drops below 2
            case Event.VIEW_CHANGE:
                passDown(evt);
                synchronized(this) {
                    View v=(View) evt.getArg();

                    // mark excluded members
                    excluded_mbrs=computeExcludedMembers(members, v.getMembers());
                    if(excluded_mbrs != null && excluded_mbrs.size() > 0) {
                        for(int i=0; i < excluded_mbrs.size(); i++) {
                            mbr=(Address) excluded_mbrs.elementAt(i);
                            entry=(FdEntry) counters.get(mbr);
                            if(entry != null)
                                entry.setExcluded(true);
                        }
                    }

                    members=v != null ? v.getMembers() : null;
                    if(members != null) {
                        num_mbrs=members.size();
                        if(num_mbrs >= 2) {
                            if(hb == null) {
                                try {
                                    start();
                                }
                                catch(Exception ex) {
                                    Trace.warn("FD_PROB.down()", "exception when calling start(): " + ex);
                                }
                            }
                        }
                        else
                            stop();
                    }
                }
                break;

            default:
                passDown(evt);
                break;
        }
    }


    /**
     Loop while more than 1 member available. Choose a member randomly (not myself !) and send a
     heartbeat. Wait for ack. If ack not received withing timeout, mcast SUSPECT message.
     */
    public void run() {
        Message hb_msg;
        FdHeader hdr;
        Address hb_dest, key;
        FdEntry entry;
        long curr_time, diff;


        if(Trace.trace)
            Trace.info("FD_PROB.run()", "heartbeat thread was started");

        while(hb != null && members.size() > 1) {

            // 1. Get a random member P (excluding ourself)
            hb_dest=getHeartbeatDest();
            if(hb_dest == null) {
                Trace.warn("FD_PROB.run()", "hb_dest is null");
                Util.sleep(gossip_interval);
                continue;
            }


            // 2. Increment own counter
            entry=(FdEntry) counters.get(local_addr);
            if(entry == null) {
                entry=new FdEntry();
                counters.put(local_addr, entry);
            }
            entry.incrementCounter();


            // 3. Send heartbeat to P
            hdr=createHeader();
            if(hdr == null)
                Trace.warn("FD_PROB.run()", "header could not be created. Heartbeat will not be sent");
            else {
                hb_msg=new Message(hb_dest, null, null);
                hb_msg.putHeader(getName(), hdr);
                if(Trace.trace)
                    Trace.info("FD_PROB.run()", "--> HEARTBEAT to " + hb_dest);
                passDown(new Event(Event.MSG, hb_msg));
            }

            if(Trace.trace)
                Trace.info("FD_PROB.run()", "own counters are " + printCounters());


            // 4. Suspect members from which we haven't heard for timeout msecs
            for(Enumeration e=counters.keys(); e.hasMoreElements();) {
                curr_time=System.currentTimeMillis();
                key=(Address) e.nextElement();
                entry=(FdEntry) counters.get(key);

                if(entry.getTimestamp() > 0 && (diff=curr_time - entry.getTimestamp()) >= timeout) {
                    if(entry.excluded()) {
                        if(diff >= 2 * timeout) {  // remove members marked as 'excluded' after 2*timeout msecs
                            counters.remove(key);
                            if(Trace.trace)
                                Trace.info("FD_PROB.run()", "removed " + key);
                            continue;
                        }
                    }
                    else {
                        if(Trace.trace)
                            Trace.info("FD_PROB.run()", "suspecting " + key);
                        passUp(new Event(Event.SUSPECT, key));
                    }
                }
            }
            Util.sleep(gossip_interval);
        } // end while

        if(Trace.trace)
            Trace.info("FD_PROB.run()", "heartbeat thread was stopped");
    }







    /* -------------------------------- Private Methods ------------------------------- */

    Address getHeartbeatDest() {
        Address retval=null;
        int r, size;
        Vector members_copy;

        if(members == null || members.size() < 2 || local_addr == null)
            return null;
        members_copy=(Vector) members.clone();
        members_copy.removeElement(local_addr); // don't select myself as heartbeat destination
        size=members_copy.size();
        r=((int) (Math.random() * (size + 1))) % size;
        retval=(Address) members_copy.elementAt(r);
        return retval;
    }


    /** Create a header containing the counters for all members */
    FdHeader createHeader() {
        int num_mbrs=counters.size(), index=0;
        FdHeader ret=null;
        Address key;
        FdEntry entry;

        if(num_mbrs <= 0)
            return null;
        ret=new FdHeader(FdHeader.HEARTBEAT, num_mbrs);
        for(Enumeration e=counters.keys(); e.hasMoreElements();) {
            key=(Address) e.nextElement();
            entry=(FdEntry) counters.get(key);
            if(entry.excluded())
                continue;
            if(index >= ret.members.length) {
                Trace.warn("FD_PROB.createHeader()", "index " + index + " is out of bounds (" +
                                                     ret.members.length + ")");
                break;
            }
            ret.members[index]=key;
            ret.counters[index]=entry.getCounter();
            index++;
        }
        return ret;
    }


    /** Set my own counters values to max(own-counter, counter) */
    void updateCounters(FdHeader hdr) {
        Address key;
        long counter;
        FdEntry entry;

        if(hdr == null || hdr.members == null || hdr.counters == null) {
            Trace.warn("FD_PROB.updateCounters()", "hdr is null or contains no counters");
            return;
        }

        for(int i=0; i < hdr.members.length; i++) {
            key=hdr.members[i];
            if(key == null) continue;
            entry=(FdEntry) counters.get(key);
            if(entry == null) {
                entry=new FdEntry(hdr.counters[i]);
                counters.put(key, entry);
                continue;
            }

            if(entry.excluded())
                continue;

            // only update counter (and adjust timestamp) if new counter is greater then old one
            entry.setCounter(Math.max(entry.getCounter(), hdr.counters[i]));
        }
    }


    /** Resets the counter for mbr */
    void updateCounter(Address mbr) {
        FdEntry entry;

        if(mbr == null) return;
        entry=(FdEntry) counters.get(mbr);
        if(entry != null)
            entry.setTimestamp();
    }


    String printCounters() {
        StringBuffer sb=new StringBuffer();
        Address mbr;
        FdEntry entry;

        for(Enumeration e=counters.keys(); e.hasMoreElements();) {
            mbr=(Address) e.nextElement();
            entry=(FdEntry) counters.get(mbr);
            sb.append("\n" + mbr + ": " + entry._toString());
        }
        return sb.toString();
    }


    Vector computeExcludedMembers(Vector old_mbrship, Vector new_mbrship) {
        Vector ret=new Vector();
        if(old_mbrship == null || new_mbrship == null) return ret;
        for(int i=0; i < old_mbrship.size(); i++)
            if(!new_mbrship.contains(old_mbrship.elementAt(i)))
                ret.addElement(old_mbrship.elementAt(i));
        return ret;
    }


    /** If hb_sender is not a member, send a SUSPECT to sender (after n pings received) */
    boolean checkPingerValidity(Object hb_sender) {
        int num_pings=0;
        Message shun_msg;
        Header hdr;

        if(hb_sender != null && members != null && !members.contains(hb_sender)) {
            if(invalid_pingers.containsKey(hb_sender)) {
                num_pings=((Integer) invalid_pingers.get(hb_sender)).intValue();
                if(num_pings >= max_tries) {
                    Trace.error(" FD_PROB.checkPingerValidity()", "sender " + hb_sender +
                                                                  " is not member in " + members + " ! Telling it to leave group");
                    shun_msg=new Message((Address) hb_sender, null, null);
                    hdr=new FdHeader(FdHeader.NOT_MEMBER);
                    shun_msg.putHeader(getName(), hdr);
                    passDown(new Event(Event.MSG, shun_msg));
                    invalid_pingers.remove(hb_sender);
                }
                else {
                    num_pings++;
                    invalid_pingers.put(hb_sender, new Integer(num_pings));
                }
            }
            else {
                num_pings++;
                invalid_pingers.put(hb_sender, new Integer(num_pings));
            }
            return false;
        }
        else
            return true;
    }


    /* ----------------------------- End of Private Methods --------------------------- */






    public static class FdHeader extends Header {
        static final int HEARTBEAT=1;  // sent periodically to a random member
        static final int NOT_MEMBER=2;  // sent to the sender, when it is not a member anymore (shunned)


        int type=HEARTBEAT;
        Address[] members=null;
        long[] counters=null;  // correlates with 'members' (same indexes)


        public FdHeader() {
        } // used for externalization

        FdHeader(int type) {
            this.type=type;
        }

        FdHeader(int type, int num_elements) {
            this(type);
            members=new Address[num_elements];
            counters=new long[num_elements];
        }


        public String toString() {
            switch(type) {
                case HEARTBEAT:
                    return "[FD_PROB: HEARTBEAT]";
                case NOT_MEMBER:
                    return "[FD_PROB: NOT_MEMBER]";
                default:
                    return "[FD_PROB: unknown type (" + type + ")]";
            }
        }

        public String printDetails() {
            StringBuffer sb=new StringBuffer();
            Address mbr;
            long c;

            if(members != null && counters != null)
                for(int i=0; i < members.length; i++) {
                    mbr=members[i];
                    if(mbr == null)
                        sb.append("\n<null>");
                    else
                        sb.append("\n" + mbr);
                    sb.append(": " + counters[i]);
                }
            return sb.toString();
        }


        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeInt(type);

            if(members != null) {
                out.writeInt(members.length);
                out.writeObject(members);
            }
            else
                out.writeInt(0);

            if(counters != null) {
                out.writeInt(counters.length);
                for(int i=0; i < counters.length; i++)
                    out.writeLong(counters[i]);
            }
            else
                out.writeInt(0);
        }


        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            int num;
            type=in.readInt();

            num=in.readInt();
            if(num == 0)
                members=null;
            else {
                members=(Address[]) in.readObject();
            }

            num=in.readInt();
            if(num == 0)
                counters=null;
            else {
                counters=new long[num];
                for(int i=0; i < counters.length; i++)
                    counters[i]=in.readLong();
            }
        }


    }


    private static class FdEntry {
        private long counter=0;       // heartbeat counter
        private long timestamp=0;     // last time the counter was incremented
        private boolean excluded=false;  // set to true if member was excluded from group


        FdEntry() {

        }

        FdEntry(long counter) {
            this.counter=counter;
            timestamp=System.currentTimeMillis();
        }


        long getCounter() {
            return counter;
        }

        long getTimestamp() {
            return timestamp;
        }

        boolean excluded() {
            return excluded;
        }


        synchronized void setCounter(long new_counter) {
            if(new_counter > counter) { // only set time if counter was incremented
                timestamp=System.currentTimeMillis();
                counter=new_counter;
            }
        }

        synchronized void incrementCounter() {
            counter++;
            timestamp=System.currentTimeMillis();
        }

        synchronized void setTimestamp() {
            timestamp=System.currentTimeMillis();
        }

        synchronized void setExcluded(boolean flag) {
            excluded=flag;
        }


        public String toString() {
            return "counter=" + counter + ", timestamp=" + timestamp + ", excluded=" + excluded;
        }

        public String _toString() {
            return "counter=" + counter + ", age=" + (System.currentTimeMillis() - timestamp) +
                    ", excluded=" + excluded;
        }
    }


}
