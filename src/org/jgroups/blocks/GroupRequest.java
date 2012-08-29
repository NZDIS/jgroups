// $Id: GroupRequest.java,v 1.1 2008/09/03 04:24:38 commerce\wuti7102 Exp $

package org.jgroups.blocks;


import java.util.Vector;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.Transport;
import org.jgroups.View;
import org.jgroups.log.Trace;
import org.jgroups.util.Command;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;




/**
 * Sends a message to all members of the group and waits for all responses (or timeout). Returns a
 * boolean value (success or failure). Results (if any) can be retrieved when done.<p>
 * The supported transport to send requests is currently either a RequestCorrelator or a generic
 * Transport. One of them has to be given in the constructor. It will then be used to send a
 * request. When a message is received by either one, the receiveResponse() of this class has to
 * be called (this class does not actively receive requests/responses itself). Also, when a view change
 * or suspicion is received, the methods viewChange() or suspect() of this class have to be called.<p>
 * When started, an array of responses, correlating to the membership, is created. Each response
 * is added to the corresponding field in the array. When all fields have been set, the algorithm
 * terminates.
 * This algorithm can optionally use a suspicion service (failure detector) to detect (and
 * exclude from the membership) fauly members. If no suspicion service is available, timeouts
 * can be used instead (see <code>execute()</code>). When done, a list of suspected members
 * can be retrieved.<p>
 * Because a channel might deliver requests, and responses to <em>different</em> requests, the
 * <code>GroupRequest</code> class cannot itself receive and process requests/responses from the
 * channel. A mechanism outside this class has to do this; it has to determine what the responses
 * are for the message sent by the <code>execute()</code> method and call <code>receiveResponse()</code>
 * to do so.<p>
 * <b>Requirements</b>: lossless delivery, e.g. acknowledgment-based message confirmation.
 * @author Bela Ban
 * @version $Revision: 1.1 $
 */
public class GroupRequest implements RspCollector, Command {
    /** return only first response */
    public static final int GET_FIRST=1;

    /** return all responses */
    public static final int GET_ALL=2;

    /** return majority (of all non-faulty members) */
    public static final int GET_MAJORITY=3;

    /** return majority (of all members, may block) */
    public static final int GET_ABS_MAJORITY=4;

    /** return n responses (may block) */
    public static final int GET_N=5;

    /** return no response (async call) */
    public static final int GET_NONE=6;

    private final short NOT_RECEIVED=0;
    private final short RECEIVED=1;
    private final short SUSPECTED=2;

    private Address membership[]=null; // current membership
    private Object responses[]=null;   // responses corresponding to membership
    private short received[]=null;     // status of response for each mbr (see above)

    /** bounded queue of suspected members */
    private Vector suspects=new Vector();

    /** list of members, changed by viewChange() */
    private Vector members=new Vector();

    /** keep suspects vector bounded */
    private int max_suspects=40;
    protected Message request_msg=null;
    protected RequestCorrelator corr=null; // either use RequestCorrelator or ...
    protected Transport transport=null;    // Transport (one of them has to be non-null)

    protected int rsp_mode=GET_ALL;
    protected boolean done=false;
    protected Object rsp_mutex=new Object();
    protected long timeout=0;
    protected int expected_mbrs=0;

    /** to generate unique request IDs (see getRequestId()) */
    private static long last_req_id=1;

    protected long req_id=-1; // request ID for this request


    /**
     @param m The message to be sent
     @param corr The request correlator to be used. A request correlator sends requests tagged with
     a unique ID and notifies the sender when matching responses are received. The
     reason <code>GroupRequest</code> uses it instead of a <code>Transport</code> is
     that multiple requests/responses might be sent/received concurrently.
     @param members The initial membership. This value reflects the membership to which the request
     is sent (and from which potential responses are expected). Is reset by reset().
     @param rsp_mode How many responses are expected. Can be
     <ol>
     <li><code>GET_ALL</code>: wait for all responses from non-suspected members.
     A suspicion service might warn
     us when a member from which a response is outstanding has crashed, so it can
     be excluded from the responses. If no suspision service is available, a
     timeout can be used (a value of 0 means wait forever). <em>If a timeout of
     0 is used, no suspicion service is available and a member from which we
     expect a response has crashed, this methods blocks forever !</em>.
     <li><code>GET_FIRST</code>: wait for the first available response.
     <li><code>GET_MAJORITY</code>: wait for the majority of all responses. The
     majority is re-computed when a member is suspected.
     <li><code>GET_ABS_MAJORITY</code>: wait for the majority of
     <em>all</em> members.
     This includes failed members, so it may block if no timeout is specified.
     <li><code>GET_N</CODE>: wait for N members.
     Return if n is >= membership+suspects.
     <li><code>GET_NONE</code>: don't wait for any response. Essentially send an
     asynchronous message to the group members.
     </ol>
     */
    public GroupRequest(Message m, RequestCorrelator corr, Vector members, int rsp_mode) {
        request_msg=m;
        this.corr=corr;
        this.rsp_mode=rsp_mode;
        reset(members);
        // suspects.removeAllElements(); // bela Aug 23 2002: made suspects bounded
    }


    /**
     @param timeout Time to wait for responses (ms). A value of <= 0 means wait indefinitely
     (e.g. if a suspicion service is available; timeouts are not needed).
     */
    public GroupRequest(
            Message m,
            RequestCorrelator corr,
            Vector members,
            int rsp_mode,
            long timeout,
            int expected_mbrs) {
        this(m, corr, members, rsp_mode);
        if(timeout > 0)
            this.timeout=timeout;
        this.expected_mbrs=expected_mbrs;
    }


    public GroupRequest(Message m, Transport transport, Vector members, int rsp_mode) {
        request_msg=m;
        this.transport=transport;
        this.rsp_mode=rsp_mode;
        reset(members);
        // suspects.removeAllElements(); // bela Aug 23 2002: make suspects bounded
    }


    /**
     * @param timeout Time to wait for responses (ms). A value of <= 0 means wait indefinitely
     *                       (e.g. if a suspicion service is available; timeouts are not needed).
     */
    public GroupRequest(
            Message m,
            Transport transport,
            Vector members,
            int rsp_mode,
            long timeout,
            int expected_mbrs) {
        this(m, transport, members, rsp_mode);
        if(timeout > 0)
            this.timeout=timeout;
        this.expected_mbrs=expected_mbrs;
    }


    /**
     * Sends the message. Returns when n responses have been received, or a
     * timeout  has occurred. <em>n</em> can be the first response, all
     * responses, or a majority  of the responses.
     */
    public boolean execute() {
        boolean retval;
        if(corr == null && transport == null) {
            Trace.error(
                    "GroupRequest.execute()",
                    "both corr and transport are null, cannot send group request");
            return false;
        }
        synchronized(rsp_mutex) {
            done=false;
            retval=doExecute(timeout);
            if(retval == false && Trace.trace)
                Trace.info("GroupRequest.execute()", "call did not execute correctly, request is " + toString());
            done=true;
            return retval;
        }
    }


    /**
     * Resets the group request, so it can be reused for another execution.
     */
    public void reset(Message m, int mode, long timeout) {
        synchronized(rsp_mutex) {
            done=false;
            request_msg=m;
            rsp_mode=mode;
            this.timeout=timeout;
            rsp_mutex.notifyAll();
        }
    }


    public void reset(Message m, final Vector members, int rsp_mode,
                      long timeout, int expected_rsps) {
        synchronized(rsp_mutex) {
            reset(m, rsp_mode, timeout);
            reset(members);
            // suspects.removeAllElements(); // bela Aug 23 2002: made suspects bounded
            this.expected_mbrs=expected_rsps;
            rsp_mutex.notifyAll();
        }
    }

    /**
     * This method sets the <code>membership</code> variable to the value of
     * <code>members</code>. It requires that the caller already hold the
     * <code>rsp_mutex</code> lock.
     * @param mbrs The new list of members
     */
    public void reset(Vector mbrs) {
        if(mbrs != null) {
            int size=mbrs.size();
            membership=new Address[size];
            responses=new Object[size];
            received=new short[size];
            for(int i=0; i < size; i++) {
                membership[i]=(Address)mbrs.elementAt(i);
                responses[i]=null;
                received[i]=NOT_RECEIVED;
            }
            // maintain local membership
            this.members.clear();
            this.members.addAll(mbrs);
        }
        else {
            if(membership != null) {
                for(int i=0; i < membership.length; i++) {
                    responses[i]=null;
                    received[i]=NOT_RECEIVED;
                }
            }
        }
    }


    /* ---------------------- Interface RspCollector -------------------------- */
    /**
     * <b>Callback</b> (called by RequestCorrelator or Transport).
     * Adds a response to the response table. When all responses have been received,
     * <code>execute()</code> returns.
     */
    public void receiveResponse(Message m) {
        Address sender=m.getSrc(), mbr;
        Object val=null;
        if(done) {
            Trace.warn(
                    "GroupRequest.receiveResponse()",
                    "command is done; cannot add response !");
            return;
        }
        if(suspects != null && suspects.size() > 0 && suspects.contains(sender)) {
            Trace.warn(
                    "GroupRequest.receiveResponse()",
                    "received response from suspected member " + sender + "; discarding");
            return;
        }
        if(m.getLength() > 0) {
            try {
                val=m.getObject();
            }
            catch(Exception e) {
                Trace.error("GroupRequest.receiveResponse()", "exception=" + e);
            }
        }
        synchronized(rsp_mutex) {
            for(int i=0; i < membership.length; i++) {
                mbr=membership[i];
                if(mbr.equals(sender)) {
                    if(received[i] == NOT_RECEIVED) {
                        responses[i]=val;
                        received[i]=RECEIVED;
                        rsp_mutex.notifyAll(); // wakes up execute()
                        break;
                    }
                }
            }
        }
        // printReceived();
    }


    /**
     * <b>Callback</b> (called by RequestCorrelator or Transport).
     * Report to <code>GroupRequest</code> that a member is reported as faulty (suspected).
     * This method would probably be called when getting a suspect message from a failure detector
     * (where available). It is used to exclude faulty members from the response list.
     */
    public void suspect(Address suspected_member) {
        Address mbr;
        synchronized(rsp_mutex) { // modify 'suspects' and 'responses' array
            for(int i=0; i < membership.length; i++) {
                mbr=membership[i];
                if(mbr.equals(suspected_member)) {
                    addSuspect(suspected_member);
                    responses[i]=null;
                    received[i]=SUSPECTED;
                    rsp_mutex.notifyAll();
                    break;
                }
            }
        }
        // printReceived();
    }


    /**
     * Any member of 'membership' that is not in the new view is flagged as
     * SUSPECTED. Any member in the new view that is <em>not</em> in the
     * membership (ie, the set of responses expected for the current RPC) will
     * <em>not</em> be added to it. If we did this we might run into the
     * following problem:
     * <ul>
     * <li>Membership is {A,B}
     * <li>A sends a synchronous group RPC (which sleeps for 60 secs in the
     * invocation handler)
     * <li>C joins while A waits for responses from A and B
     * <li>If this would generate a new view {A,B,C} and if this expanded the
     * response set to {A,B,C}, A would wait forever on C's response because C
     * never received the request in the first place, therefore won't send a
     * response.
     * </ul>
     */
    public void viewChange(View new_view) {
        Address mbr;
        Vector mbrs=new_view != null? new_view.getMembers() : null;
        if(membership == null || membership.length == 0 || mbrs == null)
            return;

        synchronized(rsp_mutex) {
            this.members.clear();
            this.members.addAll(mbrs);
            for(int i=0; i < membership.length; i++) {
                mbr=membership[i];
                if(!mbrs.contains(mbr)) {
                    addSuspect(mbr);
                    responses[i]=null;
                    received[i]=SUSPECTED;
                }
            }
            rsp_mutex.notifyAll();
        }
    }

    /* -------------------- End of Interface RspCollector ----------------------------------- */



    /** Returns the results as a RspList */
    public RspList getResults() {
        RspList retval=new RspList();
        Address sender;
        synchronized(rsp_mutex) {
            for(int i=0; i < membership.length; i++) {
                sender=membership[i];
                switch(received[i]) {
                    case SUSPECTED:
                        retval.addSuspect(sender);
                        break;
                    case RECEIVED:
                        retval.addRsp(sender, responses[i]);
                        break;
                    case NOT_RECEIVED:
                        retval.addNotReceived(sender);
                        break;
                }
            }
            return retval;
        }
    }


    public String toString() {
        StringBuffer ret=new StringBuffer();
        ret.append("[GroupRequest:\n");
        ret.append("req_id=").append(req_id).append("\n");
        ret.append("members: ");
        for(int i=0; i < membership.length; i++)
            ret.append(membership[i] + " ");
        ret.append("\nresponses: ");
        for(int i=0; i < responses.length; i++)
            ret.append(responses[i] + " ");
        if(suspects.size() > 0)
            ret.append("\nsuspects: " + suspects);
        ret.append("\nrequest_msg: " + request_msg);
        ret.append("\nrsp_mode: " + rsp_mode);
        ret.append("\ndone: " + done);
        ret.append("\ntimeout: " + timeout);
        ret.append("\nexpected_mbrs: " + expected_mbrs);
        ret.append("\n]");
        return ret.toString();
    }


    public int getNumSuspects() {
        return suspects.size();
    }


    public Vector getSuspects() {
        return suspects;
    }


    public boolean isDone() {
        return done;
    }



    /* --------------------------------- Private Methods -------------------------------------*/

    protected int determineMajority(int i) {
        return i < 2? i : (i / 2) + 1;
    }

    /** Generates a new unique request ID */
    private static synchronized long getRequestId() {
        long result=System.currentTimeMillis();
        if(result <= last_req_id) {
            result=last_req_id + 1;
        }
        last_req_id=result;
        return result;
    }

    /** This method runs with rsp_mutex locked (called by <code>execute()</code>). */
    protected boolean doExecute(long timeout) {
        long start_time=0;
        Address mbr, suspect;
        req_id=getRequestId();
        reset(null); // clear 'responses' array
        if(suspects != null) { // mark all suspects in 'received' array
            for(int i=0; i < suspects.size(); i++) {
                suspect=(Address)suspects.elementAt(i);
                for(int j=0; j < membership.length; j++) {
                    mbr=membership[j];
                    if(mbr.equals(suspect)) {
                        received[j]=SUSPECTED;
                        break; // we can break here because we ensure there are no duplicate members
                    }
                }
            }
        }

        try {
            if(corr != null) {
                java.util.List tmp=members != null? members : null;
                corr.sendRequest(
                        req_id,
                        tmp,
                        request_msg,
                        rsp_mode == GET_NONE? null : this);
            }
            else {
                transport.send(request_msg);
            }
        }
        catch(Throwable e) {
            Trace.error("GroupRequest.doExecute()", "exception=" + e);
            if(corr != null)
                corr.done(req_id);
            return false;
        }

        if(timeout <= 0) {
            while(true) { /* Wait for responses: */
                adjustMembership(); // may not be necessary, just to make sure...
                if(getResponses()) {
                    if(corr != null)
                        corr.done(req_id);
                    return true;
                }
                try {
                    rsp_mutex.wait();
                }
                catch(Exception e) {
                }
            }
        }
        else {
            start_time=System.currentTimeMillis();
            while(timeout > 0) { /* Wait for responses: */
                if(getResponses()) {
                    if(corr != null)
                        corr.done(req_id);
                    return true;
                }
                timeout=timeout - (System.currentTimeMillis() - start_time);
                if(timeout > 0) {
                    try {
                        rsp_mutex.wait(timeout);
                    }
                    catch(Exception e) {
                    }
                }
            }
            if(corr != null)
                corr.done(req_id);
            return false;
        }
    }

    protected boolean getResponses() {
        int num_not_received=getNum(NOT_RECEIVED);
        int num_received=getNum(RECEIVED);
        int num_suspected=getNum(SUSPECTED);
        int num_total=membership.length;
        int majority=determineMajority(num_total);
        switch(rsp_mode) {
            case GET_FIRST:
                if(num_received > 0)
                    return true;
                if(num_suspected >= num_total)
                // e.g. 2 members, and both suspected
                    return true;
                break;
            case GET_ALL:
                if(num_not_received > 0)
                    return false;
                return true;
            case GET_MAJORITY:
                if(num_received + num_suspected >= majority)
                    return true;
                break;
            case GET_ABS_MAJORITY:
                if(num_received >= majority)
                    return true;
                break;
            case GET_N:
                if(expected_mbrs >= num_total) {
                    rsp_mode=GET_ALL;
                    return getResponses();
                }
                if(num_received >= expected_mbrs)
                    return true;
                if(num_received + num_not_received < expected_mbrs) {
                    if(num_received + num_suspected >= expected_mbrs)
                        return true;
                    return false;
                }
                return false;
            case GET_NONE:
                return true;
            default :
                Trace.error(
                        "GroupRequest.execute()",
                        "rsp_mode " + rsp_mode + " unknown !");
                break;
        }
        return false;
    }

    /** Return number of elements of a certain type in array 'received'. Type can be RECEIVED,
     NOT_RECEIVED or SUSPECTED */
    int getNum(int type) {
        int retval=0;
        for(int i=0; i < received.length; i++)
            if(received[i] == type)
                retval++;
        return retval;
    }

    void printReceived() {
        for(int i=0; i < received.length; i++) {
            System.out.println(
                    membership[i]
                    + ": "
                    + (received[i] == NOT_RECEIVED
                       ? "NOT_RECEIVED"
                       : received[i] == RECEIVED
                         ? "RECEIVED"
                         : "SUSPECTED"));
        }
    }

    /**
     * Adjusts the 'received' array in the following way:
     * <ul>
     * <li>if a member P in 'membership' is not in 'members', P's entry in the 'received' array
     *     will be marked as SUSPECTED
     * <li>if P is 'suspected_mbr', then P's entry in the 'received' array will be marked
     *     as SUSPECTED
     * </ul>
     * This call requires exclusive access to rsp_mutex (called by getResponses() which has
     * a the rsp_mutex locked, so this should not be a problem).
     */
    void adjustMembership() {
        Address mbr;
        if(membership == null || membership.length == 0) {
            // Trace.warn("GroupRequest.adjustMembership()", "membership is null");
            return;
        }
        for(int i=0; i < membership.length; i++) {
            mbr=membership[i];
            if((this.members != null && !this.members.contains(mbr))
                    || suspects.contains(mbr)) {
                addSuspect(mbr);
                responses[i]=null;
                received[i]=SUSPECTED;
            }
        }
    }

    /**
     * Adds a member to the 'suspects' list. Removes oldest elements from 'suspects' list
     * to keep the list bounded ('max_suspects' number of elements)
     */
    void addSuspect(Address suspected_mbr) {
        if(!suspects.contains(suspected_mbr)) {
            suspects.addElement(suspected_mbr);
            while(suspects.size() >= max_suspects && suspects.size() > 0)
                suspects.remove(0); // keeps queue bounded
        }
    }
}
