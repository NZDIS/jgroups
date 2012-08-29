// $Id: FC.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import org.jgroups.stack.Protocol;
import org.jgroups.*;
import org.jgroups.log.Trace;

import java.util.*;
import java.io.ObjectOutput;
import java.io.IOException;
import java.io.ObjectInput;

/**
 * Simple flow control protocol based on a credit system. Each sender has a number of credits (bytes
 * to send). When the credits have been exhausted, the sender blocks. Each receiver also keeps track of
 * how many credits it has received from a sender. When credits for a sender fall below a threshold,
 * the receiver sends more credits to the sender. Works for both unicast and multicast messages.<br>
 * Note that this protocol must be located towards the top of the stack, or all down_threads from JChannel to this
 * protocol must be set to false ! This is in order to block JChannel.send()/JChannel.down().
 * @author Bela Ban
 * @version $Revision: 1.1 $
 */
public class FC extends Protocol {

    /** My own address */
    Address local_addr=null;

    /** HashMap<Address,Long>: keys are members, values are credits left. For each send, the
     * number of credits is decremented by the message size */
    HashMap sent=new HashMap();

    /** HashMap<Address,Long>: keys are members, values are credits left (in bytes).
     * For each receive, the credits for the sender are decremented by the size of the received message.
     * When the credits are 0, we refill and send a CREDIT message to the sender. Sender blocks until CREDIT
     * is received after reaching <tt>min_credits</tt> credits. */
    HashMap received=new HashMap();

    /** We cache the membership */
    Vector members=new Vector();

    /** List of members from whom we expect credits */
    List creditors=new ArrayList();

    /** Max number of bytes to send per receiver until an ack must
     * be received before continuing sending */
    long max_credits=50000;

    /** If credits fall below this limit, we send more credits to the sender. (We also send when
     * credits are exhausted (0 credits left)) */
    double min_threshold=0.25;

    /** Computed as <tt>max_credits</tt> times <tt>min_theshold</tt>. If explicitly set, this will
     * override the above computation */
    long min_credits=0;

    /** Current mode. True if channel was sent a BLOCK_SEND event, false if UNBLOCK_EVENT was sent */
    boolean blocking=false;

    /** When <tt>direct_blocking</tt> is enabled, block for a max number of milliseconds regardless of whether
     * credits have been received. If value is 0 we will wait forever. */
    long MAX_BLOCK_TIME=10000;

    final String name="FC";



    
    public String getName() {
        return name;
    }


    public boolean setProperties(Properties props) {
        String  str;
        boolean min_credits_set=false;

        str=props.getProperty("max_credits");
        if(str != null) {
            max_credits=Long.parseLong(str);
            props.remove("max_credits");
        }

        str=props.getProperty("min_threshold");
        if(str != null) {
            min_threshold=new Double(str).doubleValue();
            props.remove("min_threshold");
        }

        str=props.getProperty("min_credits");
        if(str != null) {
            min_credits=Long.parseLong(str);
            props.remove("min_credits");
            min_credits_set=true;
        }

        if(!min_credits_set)
            min_credits=(long)((double)max_credits * min_threshold);

        if(props.size() > 0) {
            System.err.println("FC.setProperties(): the following properties are not recognized:");
            props.list(System.out);
            return false;
        }
        return true;
    }



    public void down(Event evt) {
        synchronized(this) {
            switch(evt.getType()) {
                case Event.VIEW_CHANGE:
                    handleViewChange(((View)evt.getArg()).getMembers());
                    break;
                case Event.MSG:
                    if(handleDownMessage((Message)evt.getArg()) == false)
                        return;
                    break;
            }
        }
        passDown(evt); // this could potentially use the lower protocol's thread which may block
    }




    public void up(Event evt) {
        synchronized(this) {
            switch(evt.getType()) {
                case Event.SET_LOCAL_ADDRESS:
                    local_addr=(Address)evt.getArg();
                    break;
                case Event.VIEW_CHANGE:
                    handleViewChange(((View)evt.getArg()).getMembers());
                    break;
                case Event.MSG:
                    Message msg=(Message)evt.getArg();
                    FcHeader hdr=(FcHeader)msg.removeHeader(getName());
                    if(hdr != null) {
                        if(hdr.type == FcHeader.CREDIT) {
                            handleCredit(msg.getSrc(), hdr.num_credits);
                            return; // don't pass message up
                        }
                    }
                    else {
                        handleUpMessage(msg);
                    }
                    break;
            }
        }
        passUp(evt);
    }



    void handleCredit(Address src, long num_credits) {
        if(src == null) return;
        long  new_credits;

        new_credits=num_credits + getCredits(sent, src);
        if(Trace.trace)
            Trace.info("FC.handleCredit()", "received " + num_credits + " credits from " +
                    src + ", old credit was " + sent.get(src) + ", new credits are " +
                    new_credits + ". Creditors are\n" + printCreditors());

        //System.out.println("** received credit for " + src + ": " + num_credits +
          //      ", creditors:\n" + printCreditors());
        sent.put(src, new Long(new_credits));
        //System.out.println("** applied credit for " + src + ": " + num_credits +
          //      ", creditors:\n" + printCreditors());


        if(creditors.size() > 0) {  // we are blocked because we expect credit from one or more members
            removeCreditor(src);
            if(blocking && creditors.size() == 0) {
                unblockSender();
            }
        }
    }



    void handleUpMessage(Message msg) {
        Address src=msg.getSrc();
        long    size=Math.max(24, msg.getLength());
        long    new_credits;

        if(src == null) {
            Trace.error("FC.handleUpMessage()", "src is null");
            return;
        }

        if(src.equals(local_addr))
            return;

        if(Trace.trace)
            Trace.info("FC.handleUpMessage()", "credit for " + src + " is " + received.get(src));

        if(decrementCredit(received, src, size) == false) {
            // not enough credits left
            new_credits=max_credits - getCredits(received, src);
            if(Trace.trace)
                Trace.info("FC.handleUpMessage()", "sending " + new_credits + " credits to " + src);
            sendCredit(src, new_credits);
            replenishCredits(received, src, new_credits);
        }
    }


    void replenishCredits(HashMap received, Address dest, long new_credits) {
        long tmp_credits=getCredits(received, dest);
        tmp_credits+=new_credits;
        received.put(dest, new Long(tmp_credits));
    }

    void sendCredit(Address dest, long new_credits) {
        Message  msg=new Message(dest, null, null);
        FcHeader hdr=new FcHeader(FcHeader.CREDIT, new_credits);
        msg.putHeader(getName(), hdr);
        passDown(new Event(Event.MSG, msg));
    }


    /**
     * Handles a message. Returns true if message should be passed down, false if message should be discarded
     * @param msg
     * @return
     */
    boolean handleDownMessage(Message msg) {
        if(blocking) {
            if(Trace.trace)
                Trace.info("FC.handleDownMessage()", "blocking message to " + msg.getDest());
            while(blocking) {
                try {this.wait(MAX_BLOCK_TIME);} catch(InterruptedException e) {}
            }
        }

        if(decrMessage(msg) == false) {
            blocking=true;

            while(blocking) {
                if(Trace.trace)
                    Trace.info("FC.handleDownMessage()", "blocking " + MAX_BLOCK_TIME +
                            " msecs. Creditors are\n" + printCreditors());
                try {this.wait(MAX_BLOCK_TIME);}
                catch(Throwable e) {e.printStackTrace();}
                if(decrMessage(msg) == true)
                    return true;
                else {
                    if(Trace.trace)
                        Trace.info("FC.handleDownMessage()",
                                "insufficient credits to send message, creditors=\n" + printCreditors());
                }
            }
        }
        return true;
    }


    /**
     * Try to decrement the credits needed for this message and return true if successful, or false otherwise.
     * For unicast destinations, the credits required are subtracted from the unicast destination member, for
     * multicast messages the credits are subtracted from all current members in the group.
     * @param msg
     * @return false: will block, true: will not block
     */
    boolean decrMessage(Message msg) {
        Address dest;
        long    size;
        boolean success=true;

        if(msg == null) {
            Trace.error("FC.decrMessage()", "msg is null");
            return false;
        }
        dest=msg.getDest();
        size=Math.max(24, msg.getLength());
        if(dest != null && !dest.isMulticastAddress()) { // unicast destination
            if(dest.equals(local_addr))
                return true;
            if(Trace.trace)
                Trace.info("FC.decrMessage()", "credit for " + dest + " is " + sent.get(dest));
            if(sufficientCredit(sent, dest, size)) {
                decrementCredit(sent, dest, size);
            }
            else {
                addCreditor(dest);
                return false;
            }
        }
        else {                 // multicast destination
            for(Iterator it=members.iterator(); it.hasNext();) {
                dest=(Address)it.next();
                if(dest.equals(local_addr))
                    continue;
                if(Trace.trace)
                    Trace.info("FC.decrMessage()", "credit for " + dest + " is " + sent.get(dest));
                if(sufficientCredit(sent, dest, size) == false) {
                    addCreditor(dest);
                    success=false;
                }
            }

            if(success) {
                for(Iterator it=members.iterator(); it.hasNext();) {
                    dest=(Address) it.next();
                    decrementCredit(sent, dest, size);
                }
            }
        }
        return success;
    }




    /** If message queueing is enabled, sends queued messages and unlocks sender (if successful) */
    void unblockSender() {
        if(Trace.trace)
            Trace.info("FC.unblockSender()", "setting blocking=false");
        blocking=false;
        this.notifyAll();
    }

    String printCreditors() {
        StringBuffer sb=new StringBuffer();
        for(Iterator it=creditors.iterator(); it.hasNext();) {
            Address creditor=(Address)it.next();
            sb.append(creditor).append(": ").append(getCredits(sent, creditor)).append(" credits\n");
        }
        return sb.toString();
    }

    void addCreditor(Address mbr) {
        if(mbr != null && !creditors.contains(mbr))
            creditors.add(mbr);
    }

    void removeCreditor(Address mbr) {
        if(mbr != null)
            creditors.remove(mbr);
    }

    long getCredits(Map map, Address mbr) {
        Long tmp=(Long)map.get(mbr);
        if(tmp == null) {
            map.put(mbr, new Long(max_credits));
            return max_credits;
        }
        return tmp.longValue();
    }

    boolean sufficientCredit(Map map, Address mbr, long credits_required) {
        return checkCredit(map, mbr, credits_required, 0);
    }


    boolean checkCredit(Map map, Address mbr, long credits_required, long min_credits) {
        long    credits_left;
        Long    tmp=(Long)map.get(mbr);

        if(tmp != null) {
            credits_left=tmp.longValue();
            if(credits_left - credits_required >= min_credits) {
                return true;
            }
            else {
                if(Trace.trace)
                    Trace.info("FC.checkCredit()", "insufficient credit for " + mbr +
                            ": credits left=" + credits_left + ", credits required=" + credits_required +
                            " (min_credits=" + min_credits + ")");
                return false;
            }
        }
        else {
            map.put(mbr, new Long(max_credits - credits_required));
            return true;
        }
    }



    /**
     * Find the credits associated with <tt>dest</tt> and decrement its credits by the message size.
     * @param map
     * @param dest
     * @return Whether the required credits could successfully be subtracted from the credits left
     */
    boolean decrementCredit(HashMap map, Address dest, long credits_required) {
        long    credits_left, new_credits_left;
        Long    tmp=(Long)map.get(dest);

        if(tmp != null) {
            credits_left=tmp.longValue();
            new_credits_left=Math.max(0, credits_left - credits_required);
            map.put(dest, new Long(new_credits_left));

            if(new_credits_left >= min_credits) {
                return true;
            }
            else {
                Trace.info("FC.decrementCredit()", "not enough credits left for " +
                        dest + ": left=" + credits_left + ", required=" + credits_required);
                return false;
            }
        }
        else {
            return false;
        }
    }


    void handleViewChange(Vector mbrs) {
        Address addr;
        if(mbrs == null) return;

        if(Trace.trace)
            Trace.info("FC.handleViewChange()", "new membership: " + mbrs);

        members.clear();
        members.addAll(mbrs);

        // add members not in membership (with full credit)
        for(int i=0; i < mbrs.size(); i++) {
            addr=(Address) mbrs.elementAt(i);
            if(addr.equals(local_addr))
                continue;
            if(!sent.containsKey(addr))
                sent.put(addr, new Long(max_credits));
        }
        // remove members that left
        for(Iterator it=sent.keySet().iterator(); it.hasNext();) {
            addr=(Address)it.next();
            if(!mbrs.contains(addr))
                it.remove(); // modified the underlying map
        }

        // ditto for received messages
        for(int i=0; i < mbrs.size(); i++) {
            addr=(Address) mbrs.elementAt(i);
            if(addr.equals(local_addr))
                continue;
            if(!received.containsKey(addr))
                received.put(addr, new Long(max_credits));
        }
        for(Iterator it=received.keySet().iterator(); it.hasNext();) {
            addr=(Address) it.next();
            if(!mbrs.contains(addr))
                it.remove();
        }

        // remove all creditors which are not in the new view
        for(Iterator it=creditors.iterator(); it.hasNext();) {
            Address creditor=(Address) it.next();
            if(!mbrs.contains(creditor))
                it.remove();
        }
        if(Trace.trace)
            Trace.info("FC.handleViewChange()", "creditors are\n" + printCreditors());
        if(creditors.size() == 0 && blocking)
            unblockSender();
    }



    String dumpSentMessages() {
        StringBuffer sb=new StringBuffer();
        for(Iterator it=sent.entrySet().iterator(); it.hasNext();) {
            Map.Entry entry=(Map.Entry)it.next();
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    String dumpReceivedMessages() {
        Map tmp=(Map)received.clone();
        StringBuffer sb=new StringBuffer();
        for(Iterator it=tmp.entrySet().iterator(); it.hasNext();) {
            Map.Entry entry=(Map.Entry)it.next();
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    String dumpMessages() {
        StringBuffer sb=new StringBuffer();
        sb.append("sent:\n").append(sent).append("\n");
        sb.append("received:\n").append(received).append("\n");
        return sb.toString();
    }

    public static class FcHeader extends Header {
        public static final int CREDIT = 1;
        int  type = CREDIT;
        long num_credits=0;

        public FcHeader() {

        }

        public FcHeader(int type, long num_credits) {
            this.type=type;
            this.num_credits=num_credits;
        }



        public long size() {
            return 24;
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeInt(type);
            out.writeLong(num_credits);
        }

        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            type=in.readInt();
            num_credits=in.readLong();
        }

    }


//    public static void main(String[] args) {
//        HashMap m=new HashMap();
//        m.put("Bela", new Integer(38));
//        m.put("Jeannette", new Integer(35));
//
//        for(Iterator it=m.keySet().iterator(); it.hasNext();) {
//            String key=(String) it.next();
//            System.out.println(key);
//            if(key.equals("Bela")) {
//                //it.remove();
//                m.remove(key);
//            }
//        }
//    }

}
