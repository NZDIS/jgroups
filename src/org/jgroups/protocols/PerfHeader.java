// $Id: PerfHeader.java,v 1.1 2008/09/03 04:24:38 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import java.io.*;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;
import org.jgroups.*;
import org.jgroups.util.*;
import org.jgroups.stack.*;



/**
 * Inserted by PERF into each message. Records the time taken by each protocol to process the message to
 * which this header is attached. Travels down through the stack and up the other stack with the message.
 * @author Bela Ban
 */
public class PerfHeader extends Header {
    Object              sender=null;
    Object              receiver=null;
    long                start_time=0;                       // time when header was created
    long                end_time=0;                         // time when header was received    
    long                network_send=0;                     // time the packet was put on the network
    long                network_recv=0;                     // time the packet was received from the network
    long                network_time=0;                     // time spent on the network (between bottom layers)
    Hashtable           down=new Hashtable();               // key=protocol name, val=PerfEntry
    Hashtable           up=new Hashtable();                 // key=protocol name, val=PerfEntry
    final static int    UP=1;
    final static int    DOWN=2;
    final static String classname="org.jgroups.protocols.PerfHeader";
    static long         size=0;
     


    static {
	size=Util.sizeOf(classname);
	if(size <= 0) size=400;
    }


    // Needed for externalization
    public PerfHeader() {
    }

    
    public PerfHeader(Object sender, Object receiver) {
	this.sender=sender; this.receiver=receiver;
	start_time=System.currentTimeMillis();
    }


    public String toString() {
	return "[PerfHeader]";
    }


    
    public String printContents(boolean detailed) {
	return printContents(detailed, null);
    }


    
    public String printContents(boolean detailed, Vector prots) {
	StringBuffer sb=new StringBuffer();
	String       key;
	PerfEntry    val;
	Protocol     p;

	if(sender != null)
	    sb.append("sender=" + sender + "\n");
	if(receiver != null)
	    sb.append("receiver=" + receiver + "\n");

	if(detailed)
	    sb.append("start_time=" + start_time + "\nend_time=" + end_time + "\n");

	if(end_time >= start_time)
	    sb.append("total time=" + (end_time - start_time) + "\n");
	else
	    sb.append("total time=n/a\n");

	if(detailed) {
	    if(network_send > 0) sb.append("network_send=" + network_send + "\n");
	    if(network_recv > 0) sb.append("network_recv=" + network_recv + "\n");
	}

	if(network_time > 0)
	    sb.append("network=" + network_time + "\n");
	

	sb.append("\nDOWN\n-----\n");
	if(prots != null) {
	    for(int i=0; i < prots.size(); i++) {
		p=(Protocol)prots.elementAt(i);
		key=p.getName();
		val=(PerfEntry)down.get(key);
		sb.append(key + ":" + "\t" + val.printContents(detailed) + "\n");
	    }
	}
	else
	    for(Enumeration e=down.keys(); e.hasMoreElements();) {
		key=(String)e.nextElement();
		val=(PerfEntry)down.get(key);
		sb.append(key + ":" + "\t" + val.printContents(detailed) + "\n");
	    }
	
	sb.append("\nUP\n-----\n");
	if(prots != null) {
	    for(int i=prots.size()-1; i >= 0; i--) {
		p=(Protocol)prots.elementAt(i);
		key=p.getName();
		val=(PerfEntry)up.get(key);
		sb.append(key + ":" + "\t" + val.printContents(detailed) + "\n");
	    }
	}
	else
	    for(Enumeration e=up.keys(); e.hasMoreElements();) {
		key=(String)e.nextElement();
		val=(PerfEntry)up.get(key);
		sb.append(key + ":" + "\t" + val.printContents(detailed) + "\n");
	    }
	

	return sb.toString();
    }


    
    
    public void setEndTime() {
	end_time=System.currentTimeMillis();
    }
    
    
    public void setReceived(String prot_name, int type) {
	PerfEntry entry=getEntry(prot_name, type);
	long      t=System.currentTimeMillis();
	if(entry != null)
	    entry.setReceived(t);
    }

    public void setDone(String prot_name, int type) {
	PerfEntry entry=getEntry(prot_name, type);
	long      t=System.currentTimeMillis();
	if(entry != null)
	    entry.setDone(t);
    }

    public void setNetworkSent() {
	network_send=System.currentTimeMillis();
    }


    public void setNetworkReceived() {
	network_recv=System.currentTimeMillis();
	if(network_send > 0 && network_recv > network_send)
	    network_time=network_recv - network_send;
    }

    
    /** Adds a new entry to both hashtables */
    public void addEntry(String prot_name) {
	if(prot_name == null) return;
	up.put(prot_name, new PerfEntry());
	down.put(prot_name, new PerfEntry());
    }



    public void writeExternal(ObjectOutput out) throws IOException {
	out.writeObject(sender);
	out.writeObject(receiver);
	out.writeLong(start_time);
	out.writeLong(end_time);
	out.writeLong(network_send);
	out.writeLong(network_recv);
	out.writeLong(network_time);
	writeHashtable(down, out);
	writeHashtable(up, out);
    }



    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
	sender=in.readObject();
	receiver=in.readObject();
	start_time=in.readLong();
	end_time=in.readLong();
	network_send=in.readLong();
	network_recv=in.readLong();
	network_time=in.readLong();
	down=readHashtable(in);
	up=readHashtable(in);
    }


    public long size() {
	return size;
    }


    void writeHashtable(Hashtable h, ObjectOutput out) {
	String    key;
	PerfEntry val;

	try {
	    if(h == null) {
		out.writeInt(0);
		return;
	    }
	    out.writeInt(h.size());
	    for(Enumeration e=h.keys(); e.hasMoreElements();) {
		key=(String)e.nextElement();
		val=(PerfEntry)h.get(key);
		if(key == null || val == null) {
		    System.err.println("PerfHeader.writeHashtable(): key or val is null");
		    continue;
		}
		out.writeObject(key);
		out.writeObject(val);
	    }
	}
	catch(Exception ex) {
	    System.err.println("PerfHeader.writeHashtable(): " + ex);
	}
    }

    
    Hashtable readHashtable(ObjectInput in) {
	Hashtable h=new Hashtable();
	int       num=0;
	String    key;
	PerfEntry val;
	
	try {
	    num=in.readInt();
	    if(num == 0)
		return h;
	    for(int i=0; i < num; i++) {
		key=(String)in.readObject();
		val=(PerfEntry)in.readObject();
		h.put(key, val);
	    }
	}
	catch(Exception ex) {
	    System.err.println("PerfHeader.readHashtable(): " + ex);
	}

	return h;
    }
    



    PerfEntry getEntry(String prot_name, int type) {
	Hashtable tmp=null;
	PerfEntry entry=null;
	
	if(prot_name == null) return null;
	if(type == UP) tmp=up;
	else if(type == DOWN) tmp=down;
	if(tmp == null) return null;
	entry=(PerfEntry)tmp.get(prot_name);
	if(entry == null)
	    System.err.println("PerfHeader.getEntry(): protocol \"" + prot_name + "\" not found");
	return entry;
    }




    public static void main(String[] args) {
	PerfHeader             hdr=new PerfHeader(), hdr2;
	Message                msg, msg2;
	ByteArrayOutputStream  out_stream;
	ByteArrayInputStream   in_stream;
	ObjectOutputStream     out;
	ObjectInputStream      in;
	byte[]                 out_buf, in_buf;
	

	hdr.addEntry("GMS");
	hdr.addEntry("GMS");
	hdr.addEntry("FRAG");
	hdr.addEntry("FRAG");
	hdr.addEntry("UDP");
	hdr.addEntry("UDP");


	msg=new Message();
	msg.putHeader("PERF", hdr);


	hdr.setReceived("GMS",  PerfHeader.DOWN);
	Util.sleep(2);
	hdr.setDone("GMS",      PerfHeader.DOWN);

	hdr.setReceived("FRAG", PerfHeader.DOWN);
	Util.sleep(20);
	hdr.setDone("FRAG",     PerfHeader.DOWN);




	long len=msg.size();
	System.out.println("Size is " + len);


	hdr.setReceived("UDP",  PerfHeader.DOWN);
	Util.sleep(12);
	hdr.setDone("UDP",      PerfHeader.DOWN);


	Util.sleep(30);

	hdr.setReceived("UDP",  PerfHeader.UP);
	hdr.setDone("UDP",      PerfHeader.UP);

	hdr.setReceived("FRAG", PerfHeader.UP);
	Util.sleep(23);
	hdr.setDone("FRAG",     PerfHeader.UP);

	hdr.setReceived("GMS",  PerfHeader.UP);
	Util.sleep(3);
	hdr.setDone("GMS",      PerfHeader.UP);


	hdr.setEndTime();
	
	System.out.println(hdr.printContents(true));

	try {
	    System.out.println("Saving hdr to byte buffer");
	    out_stream=new ByteArrayOutputStream(256);
	    out=new ObjectOutputStream(out_stream);	
	    out.writeObject(msg);
	    out_buf=out_stream.toByteArray();
	    
	    System.out.println("Constructing hdr2 from byte buffer");
	    in_buf=out_buf; // ref

	    in_stream=new ByteArrayInputStream(in_buf);
	    in=new ObjectInputStream(in_stream);
	    
	    msg2=(Message)in.readObject();
	    hdr2=(PerfHeader)msg.removeHeader("PERF");
	    System.out.println(hdr2.printContents(true));
	}
	catch(Exception ex) {
	    System.err.println(ex);
	}
	

	


    }

    
}


/** Entry specific for 1 protocol layer. Records time message was received by that layer and when message was passed on */
class PerfEntry implements Externalizable {
    long received=0;
    long done=0;
    long total=-1;

    
    // Needed for externalization
    public PerfEntry() {
	
    }


    public long getReceived()       {return received;}
    public long getDone()           {return done;}
    public long getTotal()          {return total;}
    public void setReceived(long r) {received=r;}

    public void setDone(long d) {
	done=d;
	if(received > 0 && done > 0 && done >= received)
	    total=done - received;
    }

    public String toString() {
	if(total >= 0)
	    return "time: " + total;
	else
	    return "time: n/a";
    }


    public String printContents(boolean detailed) {
	StringBuffer sb=new StringBuffer();
	if(detailed) {
	    if(received > 0) sb.append("received=" + received);
	    if(done > 0) {
		if(received > 0) sb.append(", ");
		sb.append("done=" + done);
	    }
	}
	if(detailed && (received > 0 || done > 0)) sb.append(", ");
	sb.append(toString());
	return sb.toString();
    }



    public void writeExternal(ObjectOutput out) throws IOException {
	out.writeLong(received);
	out.writeLong(done);
	out.writeLong(total);
    }



    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
	received=in.readLong();
	done=in.readLong();
	total=in.readLong();
    }




}
