// $Id: FD_SIMPLE.java,v 1.1 2008/09/03 04:24:39 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.Vector;
import org.jgroups.*;
import org.jgroups.util.*;
import org.jgroups.stack.*;
import org.jgroups.log.Trace;




/**
 * Simple failure detection protocol. Periodically sends a are-you-alive message to a randomly chosen member
 * (excluding itself) and waits for a response. If a response has not been received within timeout msecs, a counter
 * associated with that member will be incremented. If the counter exceeds max_missed_hbs, that member will be
 * suspected. When a message or a heartbeat are received, the counter is reset to 0.
 * @author Bela Ban Aug 2002
 * @version $Revision: 1.1 $
 */
public class FD_SIMPLE extends Protocol {
    Address       local_addr=null;
    TimeScheduler timer=null;
    HeartbeatTask task=null;
    long          interval=3000;            // interval in msecs between are-you-alive messages    
    long          timeout=3000;             // time (in msecs) to wait for a response to are-you-alive
    Vector        members=new Vector();
    HashMap       counters=new HashMap();   // keys=Addresses, vals=Integer (count)
    int           max_missed_hbs=5;         // max number of missed responses until a member is suspected
    final String  name="FD_SIMPLE";



    public String  getName() {return "FD_SIMPLE";}

    public void    init() throws Exception {timer=stack.timer;}

    public boolean setProperties(Properties props) {
	String     str;

	str=props.getProperty("timeout");
	if(str != null) {
	    timeout=new Long(str).longValue();
	    props.remove("timeout");
	}

	str=props.getProperty("interval");
	if(str != null) {
	    interval=new Long(str).longValue();
	    props.remove("interval");
	}

	str=props.getProperty("max_missed_hbs");
	if(str != null) {
	    max_missed_hbs=Integer.parseInt(str);
	    props.remove("max_missed_hbs");
	}

	if(props.size() > 0) {
	    System.err.println("FD_SIMPLE.setProperties(): the following properties are not recognized:");
	    props.list(System.out);
	    return false;
	}
	return true;
    }


    public void stop() {
        if(task != null) {
            task.stop();
            task=null;
        }
    }


    public void up(Event evt) {
	Message  msg, rsp;
	Address  sender;
	FdHeader hdr=null;
	Object   obj;
	boolean  counter_reset=false;

	switch(evt.getType()) {

	case Event.SET_LOCAL_ADDRESS:
	    local_addr=(Address)evt.getArg();
	    break;
	    
	case Event.MSG:
	    msg=(Message)evt.getArg();
	    sender=msg.getSrc();
	    resetCounter(sender);
	    counter_reset=true;

	    hdr=(FdHeader)msg.removeHeader(name);
	    if(hdr == null)
		break;

	    switch(hdr.type) {
	    case FdHeader.ARE_YOU_ALIVE:                    // are-you-alive request, send i-am-alive response
		rsp=new Message(sender, null, null);
		rsp.putHeader(name, new FdHeader(FdHeader.I_AM_ALIVE));
		passDown(new Event(Event.MSG, rsp));
		return; // don't pass up further

	    case FdHeader.I_AM_ALIVE:
		if(Trace.trace) Trace.info("FD_SIMPLE.up()", "received I_AM_ALIVE response from " + sender);
		if(task != null)
		    task.receivedHeartbeatResponse(sender);
		if(!counter_reset)
		    resetCounter(sender);
		return;

	    default:
		Trace.warn("FD_SIMPLE.up()", "FdHeader type " + hdr.type + " not known");
		return;
	    }     
	}

	passUp(evt);                                        // pass up to the layer above us
    }




    public void down(Event evt) {
	Message msg;
	int     num_mbrs;
	Address mbr;
	View    new_view;
	Address key;
	
	switch(evt.getType()) {
	    
	// Start heartbeat thread when we have more than 1 member; stop it when membership drops below 2
	case Event.VIEW_CHANGE:
	    new_view=(View)evt.getArg();
	    members.clear();
	    members.addAll(new_view.getMembers());
	    if(new_view.size() > 1) {
		if(task == null) {
		    task=new HeartbeatTask();
		    if(Trace.trace) Trace.info("FD_SIMPLE.down()", "starting heartbeat task");
		    timer.add(task, true);
		}
	    }
	    else {
		if(task != null) {
		    if(Trace.trace) Trace.info("FD_SIMPLE.down()", "stopping heartbeat task");
		    task.stop(); // will be removed from TimeScheduler
		    task=null;
		}
	    }

	    // remove all keys from 'counters' which are not in this new view
	    for(Iterator it=counters.keySet().iterator(); it.hasNext();) {
		key=(Address)it.next();
		if(!members.contains(key)) {
		    if(Trace.trace)
			Trace.info("FD_SIMPLE.down()", "removing " + key + " from counters");
		    it.remove();
		}
	    }
	}

	passDown(evt);
    }
    







    /* -------------------------------- Private Methods ------------------------------- */
    
    Address getHeartbeatDest() {
	Address  retval=null;
	int      r, size;
	Vector   members_copy;

	if(members == null || members.size() < 2 || local_addr == null)
	    return null;
	members_copy=(Vector)members.clone();
	members_copy.removeElement(local_addr); // don't select myself as heartbeat destination
	size=members_copy.size();
	r=((int)(Math.random() * (size+1))) % size;
	retval=(Address)members_copy.elementAt(r);
	return retval;
    }


    int incrementCounter(Address mbr) {
	Integer cnt;
	int     ret=0;

	if(mbr == null) return ret;
	synchronized(counters) {
	    cnt=(Integer)counters.get(mbr);
	    if(cnt == null) {
		cnt=new Integer(0);
		counters.put(mbr, cnt);
	    }
	    else {
		ret=cnt.intValue() +1;
		counters.put(mbr, new Integer(ret));
	    }
	    return ret;
	}
    }


    void resetCounter(Address mbr) {
	if(mbr == null) return;

	synchronized(counters) {
	    counters.put(mbr, new Integer(0));
	}
    }


    String printCounters() {
	StringBuffer sb=new StringBuffer();
	Address      key;

	for(Iterator it=counters.keySet().iterator(); it.hasNext();) {
	    key=(Address)it.next();
	    sb.append(key).append(": ").append(counters.get(key)).append("\n");
	}
	return sb.toString();
    }

    /* ----------------------------- End of Private Methods --------------------------- */






    public static class FdHeader extends Header {
	static final int ARE_YOU_ALIVE = 1;  // sent periodically to a random member
	static final int I_AM_ALIVE    = 2;  // response to above message


	int type=ARE_YOU_ALIVE;

	public FdHeader() {} // used for externalization

	FdHeader(int type) {this.type=type;}


	public String toString() {
	    switch(type) {
	    case ARE_YOU_ALIVE:
		return "[FD_SIMPLE: ARE_YOU_ALIVE]";
	    case I_AM_ALIVE:
		return "[FD_SIMPLE: I_AM_ALIVE]";
	    default:
		return "[FD_SIMPLE: unknown type (" + type + ")]";
	    }
	}


	public void writeExternal(ObjectOutput out) throws IOException {
	    out.writeInt(type);
	}
	
	
	
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
	    type=in.readInt();
	}


    }


    class HeartbeatTask implements TimeScheduler.Task {
	boolean stopped=false;
	Promise promise=new Promise();
	Address dest=null;

	void stop() {
	    stopped=true;
	}
	
	public boolean cancelled() {
	    return stopped;
	}

	public long nextInterval() {
	    return interval;
	}

	public void receivedHeartbeatResponse(Address from) {
	    if(from != null && dest != null && from.equals(dest))
		promise.setResult(from);
	}
	
	public void run() {
	    Message msg;
	    int     num_missed_hbs=0;

	    dest=getHeartbeatDest();
	    if(dest == null) {
		Trace.warn("FD_SIMPLE.HeartbeatTask.run()",
			   "heartbeat destination was null, will not send ARE_YOU_ALIVE message");
		return;
	    }
	    if(Trace.trace)
		Trace.info("FD_SIMPLE.HeartbeatTask.run()", "sending ARE_YOU_ALIVE message to " + dest +
			   ", counters are\n" + printCounters());

	    promise.reset();
	    msg=new Message(dest, null, null);
	    msg.putHeader(name, new FdHeader(FdHeader.ARE_YOU_ALIVE));
	    passDown(new Event(Event.MSG, msg));
	    
	    promise.getResult(timeout);
	    num_missed_hbs=incrementCounter(dest);
	    if(num_missed_hbs >= max_missed_hbs) {
		if(Trace.trace)
		    Trace.info("FD_SIMPLE.HeartbeatTask.run()", "missed " + num_missed_hbs + " from " + dest +
			       ", suspecting member");
		passUp(new Event(Event.SUSPECT, dest));
	    }
	}
    }



}
