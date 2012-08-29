// $Id: TOTAL.java,v 1.1 2008/09/03 04:24:38 commerce\wuti7102 Exp $
package org.jgroups.protocols;



import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;
import org.jgroups.*;
import org.jgroups.util.*;
import org.jgroups.stack.*;
import org.jgroups.log.Trace;


/**
 * Implements the total ordering layer using a message sequencer
 * <p>
 *
 * The protocol guarantees that all bcast sent messages will be delivered in
 * the same order to all members. For that it uses a sequencer which assignes
 * monotonically increasing sequence ID to broadcasts. Then all group members
 * deliver the bcasts in ascending sequence ID order.
 * <p>
 * <ul>
 * <li>
 * When a bcast message comes down to this layer, it is placed in the pending
 * down queue. A bcast request is sent to the sequencer.</li>
 * <li>
 * When the sequencer receives a bcast request, it creates a bcast reply
 * message and assigns to it a monotonically increasing seqID and sends it back
 * to the source of the bcast request.</li>
 * <li>
 * When a broadcast reply is received, the corresponding bcast message is
 * assigned the received seqID. Then it is broadcasted.</li>
 * <li>
 * Received bcasts are placed in the up queue. The queue is sorted according
 * to the seqID of the bcast. Any message at the head of the up queue with a
 * seqID equal to the next expected seqID is delivered to the layer above.</li>
 * <li>
 * Unicast messages coming from the layer below are forwarded above.</li>
 * <li>
 * Unicast messages coming from the layer above are forwarded below.</li>
 * </ul>
 * <p>
 * <i>Please note that once a <code>BLOCK_OK</code> is acknowledged messages
 * coming from above are discarded!</i> Either the application must stop
 * sending messages when a <code>BLOCK</code> event is received from the
 * channel or a QUEUE layer should be placed above this one. Received messages
 * are still delivered above though.
 * <p>
 * bcast requests are retransmitted periodically until a bcast reply is
 * received. In case a BCAST_REP is on its way during a BCAST_REQ
 * retransmission, then the next BCAST_REP will be to a non-existing
 * BCAST_REQ. So, a nulll BCAST message is sent to fill the created gap in
 * the seqID of all members.
 *
 * @author i.georgiadis@doc.ic.ac.uk
 */
public class TOTAL extends Protocol {
	/**
	 * The header processed by the TOTAL layer and intended for TOTAL
	 * inter-stack communication
	 */
	public static class Header extends org.jgroups.Header {
		// Header types
		/** Null value for the tag */
		public static final int NULL_TYPE = -1;
		/** Request to broadcast by the source */
		public static final int REQ = 0;
		/** Reply to broadcast request. */
		public static final int REP = 1;
		/** Unicast message */
		public static final int UCAST = 2;
		/** Broadcast Message */
		public static final int BCAST = 3;

		/** The header's type tag */
		public int type;
		/**
		 * The ID used by the message source to match replies from the
		 * sequencer
		 */
		public long localSeqID;
		/** The ID imposing the total order of messages */
		public long seqID;

		/**
		 * used for externalization
		 */
		public Header() {}
		/**
		 * Create a header for the TOTAL layer
		 * @param type the header's type
		 * @param localSeqID the ID used by the sender of broadcasts to match
		 * requests with replies from the sequencer
		 * @param seqID the ID imposing the total order of messages
		 *
		 * @throws IllegalArgumentException if the provided header type is
		 * unknown
		 */
		public Header(int type, long localSeqID, long seqID) { super();
			switch(type) {
			case REQ:
			case REP:
			case UCAST:
			case BCAST: this.type = type; break;
			default:
				this.type = NULL_TYPE;
				throw new IllegalArgumentException("type");
			}
			this.localSeqID = localSeqID;
			this.seqID      = seqID;
		}

		/**
		 * For debugging purposes
		 */
		public String toString() {
			StringBuffer buffer = new StringBuffer();
			String typeName;
			buffer.append("[TOTAL.Header");
			switch(type) {
			case REQ: typeName = "REQ"; break;
			case REP: typeName = "REP"; break;
			case UCAST: typeName = "UCAST"; break;
			case BCAST: typeName = "BCAST"; break;
			case NULL_TYPE: typeName = "NULL_TYPE"; break;
			default: typeName = ""; break;
			}
			buffer.append(", type=" + typeName);
			buffer.append(", " + "localID=" + localSeqID);
			buffer.append(", " + "seqID=" + seqID);
			buffer.append("]");

			return(buffer.toString());
		}

		/**
		 * Manual serialization
		 */
		public void writeExternal(ObjectOutput out) throws IOException {
		out.writeInt(type);
		out.writeLong(localSeqID);
		out.writeLong(seqID);
	    }

		/**
		 * Manual deserialization
		 */
	    public void readExternal(ObjectInput in) throws IOException,
			ClassNotFoundException {
		type=in.readInt();
		localSeqID=in.readLong();
		seqID=in.readLong();
	    }
	}


	/**
	 * The retransmission listener - It is called by the
	 * <code>AckSenderWindow</code> when a retransmission should occur
	 */
	private class Command implements AckSenderWindow.RetransmitCommand {
		public Command() {}
		public void retransmit(long seqNo, Message msg) {
			_retransmitBcastRequest(seqNo);
		}
	}


	/** Protocol name */
	private static final String PROT_NAME = "TOTAL";
	/** Property names */
	private static final String TRACE_PROP = "trace";

	/** Average time between broadcast request retransmissions */
	private long[] AVG_RETRANSMIT_INTERVAL = new long[]{1000,2000,3000,4000};

	/** Null value for the IDs */
	private static final long NULL_ID = -1;
	// Layer sending states
	/** No group has been joined yet */
	private static final int NULL_STATE = -1;
	/** When set, all messages are sent/received */
	private static final int RUN = 0;
	/**
	 * When set, only session-specific messages are sent/received, i.e. only
	 * messages essential to the session's integrity
	 */
	private static final int FLUSH = 1;
	/** No message is sent to the layer below */
	private static final int BLOCK = 2;


	/** The state lock allowing multiple reads or a single write */
	private RWLock stateLock = new RWLock();
	/** Protocol layer message-sending state */
	private int state = NULL_STATE;
	/** The address of this stack */
	private Address addr = null;
	/** The address of the sequencer */
	private Address sequencerAddr = null;
	/**
	 * The sequencer's seq ID. The ID of the most recently broadcast reply
	 * message
	 */
	private long sequencerSeqID = NULL_ID;
	/**
	 * The local sequence ID, i.e. the ID sent with the last broadcast request
	 * message. This is increased with every broadcast request sent to the
	 * sequencer and it's used to match the requests with the sequencer's
	 * replies
	 */
	private long localSeqID = NULL_ID;
	/**
	 * The total order sequence ID. This is the ID of the most recently
	 * delivered broadcast message. As the sequence IDs are increasing without
	 * gaps, this is used to detect missing broadcast messages
	 */
	private long seqID = NULL_ID;
	/**
	 * The list of unanswered broadcast requests to the sequencer. The entries
	 * are stored in increasing local sequence ID, i.e. in the order they were
	 *
	 * sent localSeqID -> Broadcast msg to be sent.
	 */
	private SortedMap reqTbl;
	/**
	 * The list of received broadcast messages that haven't yet been delivered
	 * to the layer above. The entries are stored in increasing sequence ID,
	 * i.e. in the order they must be delivered above
	 *
	 * seqID -> Received broadcast msg
	 */
	private SortedMap upTbl;
	/** Retranmitter for pending broadcast requests */
	private AckSenderWindow retransmitter;


	/**
	 * Print addresses in host_ip:port form to bypass DNS
	 */
	private String _addrToString(Object addr) { return (
		addr == null? "<null>" :
			((addr instanceof org.jgroups.stack.IpAddress)?
			(((org.jgroups.stack.IpAddress)addr).getIpAddress(
			).getHostAddress() + ":" +
			((org.jgroups.stack.IpAddress)addr).getPort()) :
			addr.toString())
		);
	}


	/** @return this protocol's name */
	private String _getName() {
		return(PROT_NAME);
	}

	/**
	 * Configure the protocol based on the given list of properties
	 *
	 * @param properties the list of properties to use to setup this layer
	 * @return false if there was any unrecognized property or a property with
	 * an invalid value
	 */
	private boolean _setProperties(Properties properties) {
		String value;

		// trace
		// Parse & remove property but ignore it; use Trace.trace instead
		value  = properties.getProperty(TRACE_PROP);
		if (value != null) properties.remove(TRACE_PROP);
		if (properties.size() > 0) {
		    Trace.error("TOTAL.setProperties()", "The following properties are not " +
				"recognized: " + properties.toString());
			return(false);
		}
		return(true);
	}

	/**
	 * Events that some layer below must handle
	 *
	 * @return the set of <code>Event</code>s that must be handled by some layer
	 * below
	 */
	Vector _requiredDownServices() {
		Vector services = new Vector();

		return(services);
	}
	/**
	 * Events that some layer above must handle
	 *
	 * @return the set of <code>Event</code>s that must be handled by some
	 * layer above
	 */
	Vector _requiredUpServices() {
		Vector services = new Vector();

		return(services);
	}


	/**
	 * Extract as many messages as possible from the pending up queue and send
	 * them to the layer above
	 */
	private void _deliverBcast() {
		Message msg;
		Header  header;

		synchronized(upTbl) {
		while((msg = (Message)upTbl.remove(new Long(seqID+1))) != null) {
			header = (Header)msg.removeHeader(getName());
			if (header.localSeqID != NULL_ID) passUp(new Event(Event.MSG, msg));
			++seqID;
		}
		} // synchronized(upTbl)
	}


	/**
	 * Add all undelivered bcasts sent by this member in the req queue and then
	 * replay this queue
	 */
	private void _replayBcast() {
		Iterator it;
		Message  msg;
		Header   header;

		// i. Remove all undelivered bcasts sent by this member and place them
		// again in the pending bcast req queue

		synchronized(upTbl) {
		if (upTbl.size() > 0)
			Trace.info("TOTAL", "Replaying undelivered bcasts");

		it = upTbl.entrySet().iterator(); while(it.hasNext()) {
			msg = (Message)((Map.Entry)it.next()).getValue(); it.remove();
			if (!msg.getSrc().equals(addr)) {
			    Trace.info("TOTAL", "During replay: " +
				       "discarding BCAST[" +
				       ((TOTAL.Header)msg.getHeader(getName())).seqID +
				       "] from " + _addrToString(msg.getSrc()));
			    continue;
			}
			header = (Header)msg.removeHeader(getName());
			if (header.localSeqID == NULL_ID) continue;
			_sendBcastRequest(msg, header.localSeqID);
		}
		} // synchronized(upTbl)
	}


	/**
	 * Send a unicast message: Add a <code>UCAST</code> header
	 *
	 * @param msg the message to unicast
	 * @return the message to send
	 */
	private Message _sendUcast(Message msg) {
		msg.putHeader(getName(), new Header(Header.UCAST, NULL_ID, NULL_ID));
		return(msg);
	}


	/**
	 * Replace the original message with a broadcast request sent to the
	 * sequencer. The original bcast message is stored locally until a reply to
	 * bcast is received from the sequencer. This function has the side-effect
	 * of increasing the <code>localSeqID</code>
	 *
	 * @param msg the message to broadcast
	 */
	private void _sendBcastRequest(Message msg) {
		_sendBcastRequest(msg, ++localSeqID);
	}


	/**
	 * Replace the original message with a broadcast request sent to the
	 * sequencer. The original bcast message is stored locally until a reply
	 * to bcast is received from the sequencer
	 *
	 * @param msg the message to broadcast
	 * @param id the local sequence ID to use
	 */
	private void _sendBcastRequest(Message msg, long id) {

		// i. Store away the message while waiting for the sequencer's reply
		// ii. Send a bcast request immediatelly and also schedule a
		// retransmission
		synchronized(reqTbl) {
		reqTbl.put(new Long(id), msg);
		}
		_transmitBcastRequest(id);
		retransmitter.add(id, msg);
	}


	/**
	 * Send the bcast request with the given localSeqID
	 *
	 * @param seqID the local sequence id of the
	 */
	private void _transmitBcastRequest(long seqID) {
		Message reqMsg;

		// i. If NULL_STATE, then ignore, just transient state before
		// shutting down the retransmission thread
		// ii. If blocked, be patient - reschedule
		// iii. If the request is not pending any more, acknowledge it
		// iv. Create a broadcast request and send it to the sequencer

		if (state == NULL_STATE) {
			Trace.info("TOTAL", "Transmit BCAST_REQ[" + seqID + "] in NULL_STATE");
			return;
		}
		if (state == BLOCK) return;

		synchronized(reqTbl) {
		if (!reqTbl.containsKey(new Long(seqID))) {
			retransmitter.ack(seqID);
			return;
		}
		}
		reqMsg = new Message(sequencerAddr, addr, new byte[0]);
		reqMsg.putHeader(getName(), new Header(Header.REQ, seqID, NULL_ID));

		passDown(new Event(Event.MSG, reqMsg));
	}


	/**
	 * Receive a unicast message: Remove the <code>UCAST</code> header
	 *
	 * @param msg the received unicast message
	 */
	private void _recvUcast(Message msg) {
		msg.removeHeader(getName());
	}

	/**
	 * Receive a broadcast message: Put it in the pending up queue and then
	 * try to deliver above as many messages as possible
	 *
	 * @param msg the received broadcast message
	 */
	private void _recvBcast(Message msg) {
		Header header = (Header)msg.getHeader(getName());

		// i. Put the message in the up pending queue only if it's not
		// already there, as it seems that the event may be received
		// multiple times before a view change when all members are
		// negotiating a common set of stable msgs
		//
		// ii. Deliver as many messages as possible

		synchronized(upTbl) {
		if (header.seqID <= seqID)
			return;
		upTbl.put(new Long(header.seqID), msg);
		}

		_deliverBcast();
	}


	/**
	 * Received a bcast request - Ignore if not the sequencer, else send a
	 * bcast reply
	 *
	 * @param msg the broadcast request message
	 */
	private void _recvBcastRequest(Message msg) {
		Header  header;
		Message repMsg;

		// i. If blocked, discard the bcast request
		// ii. Assign a seqID to the message and send it back to the requestor

		if (!addr.equals(sequencerAddr)) {
			Trace.error("TOTAL", "Received bcast request " +
				    "but not a sequencer");
			return;
		}
		if (state == BLOCK) {
			Trace.info("TOTAL", "Blocked, discard bcast req");
			return;
		}
		header = (Header)msg.getHeader(getName());
		++sequencerSeqID;
		repMsg = new Message(msg.getSrc(), addr, new byte[0]);
		repMsg.putHeader(getName(), new Header(Header.REP, header.localSeqID,
			sequencerSeqID));

		passDown(new Event(Event.MSG, repMsg));
	}


	/**
	 * Received a bcast reply - Match with the pending bcast request and move
	 * the message in the list of messages to be delivered above
	 *
	 * @param header the header of the bcast reply
	 */
	private void _recvBcastReply(Header header) {
		Message msg;
		long    id;

		// i. If blocked, discard the bcast reply
		//
		// ii. Assign the received seqID to the message and broadcast it
		//
		// iii.
		// - Acknowledge the message to the retransmitter
		// - If non-existent BCAST_REQ, send a fake bcast to avoid seqID gaps
		// - If localID == NULL_ID, it's a null BCAST, else normal BCAST
		// - Set the seq ID of the message to the one sent by the sequencer

		if (state == BLOCK) {
			Trace.info("TOTAL", "Blocked, discard bcast rep");
			return;
		}

		synchronized(reqTbl) {
		msg = (Message)reqTbl.remove(new Long(header.localSeqID));
		}

		if (msg != null) {
			retransmitter.ack(header.localSeqID);
			id = header.localSeqID;
		} else {
			Trace.info("TOTAL", "Bcast reply to " +
				   "non-existent BCAST_REQ[" + header.localSeqID +
				   "], Sending NULL bcast");
			id  = NULL_ID;
			msg = new Message(null, addr, new byte[0]);
		}
		msg.putHeader(getName(), new Header(Header.BCAST, id, header.seqID));

		passDown(new Event(Event.MSG, msg));
	}


	/**
	 * Resend the bcast request with the given localSeqID
	 *
	 * @param seqID the local sequence id of the
	 */
	private void _retransmitBcastRequest(long seqID) {
		// *** Get a shared lock
		try { stateLock.readLock(); try {

		Trace.info("TOTAL", "Retransmit BCAST_REQ[" + seqID + "]");
		_transmitBcastRequest(seqID);

		// ** Revoke the shared lock
		} finally { stateLock.readUnlock(); }
		} catch(RWLock.IntException ex) {
		Trace.error("TOTAL", ex.getMessage());
		}
	}


	/* Up event handlers
	 * If the return value is true the event travels further up the stack
	 * else it won't be forwarded
	 */

	/**
	 * Prepare for a VIEW_CHANGE: switch to flushing state
	 *
	 * @return true if the event is to be forwarded further up
	 */
	private boolean _upBlock() {
		// *** Get an exclusive lock
		try { stateLock.writeLock(); try {

		state = FLUSH;

		// *** Revoke the exclusive lock
		} finally { stateLock.writeUnlock(); }
		} catch(RWLock.IntException ex) {
		Trace.error("TOTAL", ex.getMessage());
		}

		return(true);
	}


	/**
	 * Handle an up MSG event
	 *
	 * @param event the MSG event
	 * @return true if the event is to be forwarded further up
	 */
	private boolean _upMsg(Event event) {
		Message msg;
		Object  obj;
		Header  header;

		// *** Get a shared lock
		try { stateLock.readLock(); try {

		// If NULL_STATE, shouldn't receive any msg on the up queue!
		if (state == NULL_STATE) {
			Trace.error("TOTAL", "Up msg in NULL_STATE");
			return(false);
		}

		// Peek the header:
		//
		// (UCAST) A unicast message - Send up the stack
		// (BCAST) A broadcast message - Handle specially
		// (REQ) A broadcast request - Handle specially
		// (REP) A broadcast reply from the sequencer - Handle specially
		msg = (Message)event.getArg();
		if (!((obj = msg.getHeader(getName())) instanceof TOTAL.Header)) {
			Trace.error("TOTAL", "No TOTAL.Header found");
			return(false);
		}
		header = (Header)obj;

		switch(header.type) {
		case Header.UCAST:
			_recvUcast(msg);
			return(true);
		case Header.BCAST:
			_recvBcast(msg);
			return(false);
		case Header.REQ:
			_recvBcastRequest(msg);
			return(false);
		case Header.REP:
			_recvBcastReply(header);
			return(false);
		default:
			Trace.error("TOTAL", "Unknown header type");
			return(false);
		}

		// ** Revoke the shared lock
		} finally { stateLock.readUnlock(); }
		} catch(RWLock.IntException ex) {
		Trace.error("TOTAL", ex.getMessage());
		}

		return(true);
	}


	/**
	 * Set the address of this group member
	 *
	 * @param event the SET_LOCAL_ADDRESS event
	 * @return true if event should be forwarded further up
	 */
	private boolean _upSetLocalAddress(Event event) {
		// *** Get an exclusive lock
		try { stateLock.writeLock(); try {

		addr  = (Address)event.getArg();

		// *** Revoke the exclusive lock
		} finally { stateLock.writeUnlock(); }
		} catch(RWLock.IntException ex) {
		Trace.error("TOTAL", ex.getMessage());
		}
		return(true);
	}


	/**
	 * Handle view changes
	 *
	 * param event the VIEW_CHANGE event
	 * @return true if the event should be forwarded to the layer above
	 */
	private boolean _upViewChange(Event event) {
		Object oldSequencerAddr;

		// *** Get an exclusive lock
		try { stateLock.writeLock(); try {

		state = RUN;

		// i. See if this member is the sequencer
		// ii. If this is the sequencer, reset the sequencer's sequence ID
		// iii. Reset the last received sequence ID
		//
		// iv. Replay undelivered bcasts: Put all the undelivered bcasts
		// sent by us back to the req queue and discard the rest
		oldSequencerAddr = sequencerAddr;
		sequencerAddr   =
			(Address)((View)event.getArg()).getMembers().elementAt(0);
		if (addr.equals(sequencerAddr)) {
			sequencerSeqID = NULL_ID;
			if ((oldSequencerAddr == null) ||
				(!addr.equals(oldSequencerAddr)))
				Trace.info("TOTAL", "I'm the new sequencer");
		}
		seqID = NULL_ID;
		_replayBcast();

		// *** Revoke the exclusive lock
		} finally { stateLock.writeUnlock(); }
		} catch(RWLock.IntException ex) {
		Trace.error("TOTAL", ex.getMessage());
		}

		return(true);
	}


	/*
	 * Down event handlers
	 * If the return value is true the event travels further down the stack
	 * else it won't be forwarded
	 */


	/**
	 * Blocking confirmed - No messages should come from above until a
	 * VIEW_CHANGE event is received. Switch to blocking state.
	 *
	 * @return true if event should travel further down
	 */
	private boolean _downBlockOk() {
		// *** Get an exclusive lock
		try { stateLock.writeLock(); try {

		state = BLOCK;

		// *** Revoke the exclusive lock
		} finally { stateLock.writeUnlock(); }
		} catch(RWLock.IntException ex) {
		Trace.error("TOTAL", ex.getMessage());
		}

		return(true);
	}


	/**
	 * A MSG event travelling down the stack. Forward unicast messages, treat
	 * specially the broadcast messages.<br>
	 *
	 * If in <code>BLOCK</code> state, i.e. it has replied to a
	 * <code>BLOCk_OK</code> and hasn't yet received a
	 * <code>VIEW_CHANGE</code> event, messages are discarded<br>
	 *
	 * If in <code>FLUSH</code> state, forward unicast but queue broadcasts
	 *
	 * @param event the MSG event
	 * @return true if event should travel further down
	 */
	private boolean _downMsg(Event event) {
		Message msg;

		// *** Get a shared lock
		try { stateLock.readLock(); try {

		// i. Discard all msgs, if in NULL_STATE
		// ii. Discard all msgs, if blocked
		if (state == NULL_STATE) {
			Trace.error("TOTAL", "Discard msg in NULL_STATE");
			return(false);
		}
		if (state == BLOCK) {
			Trace.error("TOTAL", "Blocked, discard msg");
			return(false);
		}

		msg = (Message)event.getArg();
		if (msg.getDest() == null) {
			_sendBcastRequest(msg);
			return(false);
		} else {
			msg = _sendUcast(msg);
			event.setArg(msg);
		}

		// ** Revoke the shared lock
		} finally { stateLock.readUnlock(); }
		} catch(RWLock.IntException ex) {
		Trace.error("TOTAL", ex.getMessage());
		}

		return(true);
	}


    

	/**
	 * Prepare this layer to receive messages from above
	 */
	public void start() throws Exception {
            TimeScheduler timer;
            
            timer = stack != null ? stack.timer : null;
	    if(timer == null)
                throw new Exception("TOTAL.start(): timer is null");
            
            reqTbl       = new TreeMap();
            upTbl        = new TreeMap();
            retransmitter = new AckSenderWindow(new Command(), AVG_RETRANSMIT_INTERVAL);
	}
    



	/**
	 * Handle the stop() method travelling down the stack.
	 * <p>
	 * The local addr is set to null, since after a Start->Stop->Start
	 * sequence this member's addr is not guaranteed to be the same
	 *
	 */
    public void stop() {
        // *** Get an exclusive lock
        try {
            stateLock.writeLock(); 
            try {
                state = NULL_STATE;
                retransmitter.reset();
                reqTbl.clear();
                upTbl.clear();
                addr = null;
                
                // *** Revoke the exclusive lock
            }
            finally {
                stateLock.writeUnlock();
            }
        }
        catch(RWLock.IntException ex) {
            Trace.error("TOTAL.stop()", ex.getMessage());
        }
    }


	/**
	 * Process an event coming from the layer below
	 *
	 * @param event the event to process
	 */
	private void _up(Event event) {
		switch(event.getType()) {
		case Event.BLOCK:
			if (!_upBlock()) return;
			break;
		case Event.MSG:
			if (!_upMsg(event)) return;
			break;
		case Event.SET_LOCAL_ADDRESS:
			if (!_upSetLocalAddress(event)) return;
			break;
		case Event.VIEW_CHANGE:
			if (!_upViewChange(event)) return;
			break;
		default: break;
		}

		passUp(event);
	}


	/**
	 * Process an event coming from the layer above
	 *
	 * @param event the event to process
	 */
	private void _down(Event event) {
		switch(event.getType()) {
		case Event.BLOCK_OK:
			if (!_downBlockOk()) return;
			break;
		case Event.MSG:
			if (!_downMsg(event)) return;
			break;
		default: break;
		}

		passDown(event);
	}


	/**
	 * Create the TOTAL layer
	 */
	public TOTAL() {}


	// Methods deriving from <code>Protocol</code>
	// javadoc inherited from superclass
	public String getName() {
		return(_getName());
	}
	// javadoc inherited from superclass
	public boolean setProperties(Properties properties) {
		return(_setProperties(properties));
	}
	// javadoc inherited from superclass
	public Vector requiredDownServices() {
		return(_requiredDownServices());
	}
	// javadoc inherited from superclass
	public Vector requiredUpServices() {
		return(_requiredUpServices());
	}
	// javadoc inherited from superclass
	public void up(Event event) {
		_up(event);
	}
	// javadoc inherited from superclass
	public void down(Event event) {
		_down(event);
	}
}
