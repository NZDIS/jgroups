// $Id: Router.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups.stack;


import org.jgroups.Address;
import org.jgroups.log.Trace;
import org.jgroups.util.List;
import org.jgroups.util.Util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;




/**
 * Router for TCP based group comunication (using layer TCP instead of UDP). Instead of the TCP
 * layer sending packets point-to-point to each other member, it sends the packet to the router
 * which - depending on the target address - multicasts or unicasts it to the group / or single
 *  member.<p>
 * This class is especially interesting for applets which cannot directly make connections
 * (neither UDP nor TCP) to a host different from the one they were loaded from. Therefore,
 * an applet would create a normal channel plus protocol stack, but the bottom layer would have
 * to be the TCP layer which sends all packets point-to-point (over a TCP connection) to the
 * router, which in turn forwards them to their end location(s) (also over TCP). A centralized
 * router would therefore have to be running on the host the applet was loaded from.<p>
 * An alternative for running JGroups in an applet (IP multicast is not allows in applets as of
 * 1.2), is to use point-to-point UDP communication via the gossip server. However, then the appplet
 * has to be signed which involves additional administrative effort on the part of the user.
 * @author Bela Ban
 */
public class Router {
    Hashtable    groups=new Hashtable();  // groupname - vector of AddressEntry's
    int          port=8080;
    ServerSocket srv_sock=null;
    InetAddress  bind_address;

    public static final int GET=-10;
    public static final int REGISTER=-11;
    public static final int DUMP=-21;


    public Router(int port) throws Exception {
        this.port=port;
        srv_sock=new ServerSocket(port, 50);  // backlog of 50 connections
    }

    public Router(int port, InetAddress bind_address) throws Exception {
        this.port=port;
        this.bind_address=bind_address;
        srv_sock=new ServerSocket(port, 50, bind_address);  // backlog of 50 connections
    }


    public void start() {
        Socket sock;
        DataInputStream input;
        DataOutputStream output;
        Address peer_addr;
        byte[] buf;
        int len, type;
        String gname;
        Date d;

        if(bind_address == null) bind_address=srv_sock.getInetAddress();
        d=new Date();
        if(Trace.trace) {
            Trace.info("Router.start()", "Router started at " + d);
            Trace.info("Router.start()", "Listening on port " + port + " bound on address " + bind_address + "\n");
        }
        d=null;

        while(true) {
            try {
                sock=srv_sock.accept();
                sock.setSoLinger(true, 500);
                peer_addr=new org.jgroups.stack.IpAddress(sock.getInetAddress(), sock.getPort());
                output=new DataOutputStream(sock.getOutputStream());

                // return the address of the peer so it can set it
                buf=Util.objectToByteBuffer(peer_addr);
                output.writeInt(buf.length);
                output.write(buf, 0, buf.length);


                // We can have 2 kinds of messages at this point: GET requests or REGISTER requests.
                // GET requests are processed right here, REGISTRATION requests cause the spawning of
                // a separate thread handling it (long running thread as it will do the message routing
                // on behalf of that client for the duration of the client's lifetime).

                input=new DataInputStream(sock.getInputStream());
                type=input.readInt();
                gname=input.readUTF();

                switch(type) {
                    case Router.GET:
                        processGetRequest(sock, output, gname); // closes sock after processing
                        break;
                    case Router.DUMP:
                        processDumpRequest(peer_addr, sock, output); // closes sock after processing
                        break;
                    case Router.REGISTER:
                        Address addr;
                        len=input.readInt();
                        buf=new byte[len];
                        input.readFully(buf, 0, buf.length); // read Address
                        addr=(Address)Util.objectFromByteBuffer(buf);
                        addEntry(gname, new AddressEntry(addr, sock, output));
                        new SocketThread(sock, input).start();
                        break;
                    default:
                        Trace.error("Router.start()", "request of type " + type + " not recognized");
                        continue;
                }
            }
            catch(Exception e) {
                Trace.error("Router.start()", "exception=" + e);
                continue;
            }
        }
    }


    public void stop() {

    }

    /**
     Gets the members of group 'groupname'. Returns them as a List of Addresses.
     */
    void processGetRequest(Socket sock, DataOutputStream output, String groupname) {
        List grpmbrs=(List)groups.get(groupname), ret=null;
        AddressEntry entry;
        byte[] buf;

        if(Trace.debug) Trace.debug("Router.processGetRequest()", "groupname=" + groupname + ", result=" + grpmbrs);

        if(grpmbrs != null && grpmbrs.size() > 0) {
            ret=new List();
            for(Enumeration e=grpmbrs.elements(); e.hasMoreElements();) {
                entry=(AddressEntry)e.nextElement();
                ret.add(entry.addr);
            }
        }
        try {
            if(ret == null || ret.size() == 0) {
                output.writeInt(0);
            }
            else {
                buf=Util.objectToByteBuffer(ret);
                output.writeInt(buf.length);
                output.write(buf, 0, buf.length);
            }
        }
        catch(Exception e) {
            Trace.error("Router.processGetRequest()", "exception=" + e);
        }
        finally {
            try {
                if(output != null)
                    output.close();
                sock.close();
            }
            catch(Exception e) {
            }
        }
    }


    /**
     * Dumps the routing table on the wire, as String.
     **/
    void processDumpRequest(Address peerAddress, Socket sock, DataOutputStream output) {

        if(Trace.debug) Trace.debug("Router.processDumpRequest()", "request from " + peerAddress);

        StringBuffer sb=new StringBuffer();
        synchronized(groups) {
            if(groups.size() == 0) {
                sb.append("empty routing table");
            }
            else {
                for(Iterator i=groups.keySet().iterator(); i.hasNext();) {
                    String gname=(String)i.next();
                    sb.append("GROUP: '" + gname + "'\n");
                    List l=(List)groups.get(gname);
                    if(l == null) {
                        sb.append("\tnull list of addresses\n");
                    }
                    else
                        if(l.size() == 0) {
                            sb.append("\tempty list of addresses\n");
                        }
                        else {
                            for(Enumeration e=l.elements(); e.hasMoreElements();) {
                                AddressEntry ae=(AddressEntry)e.nextElement();
                                sb.append("\t");
                                sb.append(ae.toString());
                                sb.append("\n");
                            }
                        }
                }
            }
        }
        try {
            output.writeUTF(sb.toString());
        }
        catch(Exception e) {
            Trace.error("Router.processDumpRequest()",
                        "Error sending the answer back to the client: " + e);
        }
        finally {
            try {
                if(output != null) {
                    output.close();
                }
            }
            catch(Exception e) {
                Trace.error("Router.processDumpRequest()",
                            "Error closing the output stream: " + e);
            }
            try {
                sock.close();
            }
            catch(Exception e) {
                Trace.error("Router.processDumpRequest()",
                            "Error closing the socket: " + e);
            }
        }
    }

    synchronized void route(Address dest, String dest_group, byte[] msg) {

        if(dest == null) { // send to all members in group dest.getChannelName()
            if(dest_group == null) {
                Trace.error("Router.route()", "both dest address and group are null");
                return;
            }
            else {
                sendToAllMembersInGroup(dest_group, msg);
            }
        }
        else {                  // send to destination address
            DataOutputStream out=findSocket(dest);
            if(out != null)
                sendToMember(out, msg);
            else
                Trace.error("Router.route()", "routing of message to " + dest + " failed; outstream is null !");
        }
    }


    void addEntry(String groupname, AddressEntry e) {
        List val;
        AddressEntry old_entry;

        // Util.print("addEntry(" + groupname + ", " + e + ")");

        if(groupname == null) {
            Trace.error("Router.addEntry()", "groupname was null, not added !");
            return;
        }

        synchronized(groups) {
            val=(List)groups.get(groupname);

            if(val == null) {
                val=new List();
                groups.put(groupname, val);
            }
            if(val.contains(e)) {
                old_entry=(AddressEntry)val.removeElement(e);
                if(old_entry != null)
                    old_entry.destroy();
            }
            val.add(e);
        }
    }


    void removeEntry(Socket sock) {
        List val;
        AddressEntry entry;

        synchronized(groups) {
            for(Enumeration e=groups.keys(); e.hasMoreElements();) {
                val=(List)groups.get(e.nextElement());

                for(Enumeration e2=val.elements(); e2.hasMoreElements();) {
                    entry=(AddressEntry)e2.nextElement();
                    if(entry.sock == sock) {
                        try {
                            entry.sock.close();
                        }
                        catch(Exception ex) {
                        }
                        //Util.print("Removing entry " + entry);
                        val.removeElement(entry);
                        return;
                    }
                }
            }
        }
    }


    void removeEntry(OutputStream out) {
        List val;
        AddressEntry entry;

        synchronized(groups) {
            for(Enumeration e=groups.keys(); e.hasMoreElements();) {
                val=(List)groups.get(e.nextElement());

                for(Enumeration e2=val.elements(); e2.hasMoreElements();) {
                    entry=(AddressEntry)e2.nextElement();
                    if(entry.output == out) {
                        try {
                            if(entry.sock != null)
                                entry.sock.close();
                        }
                        catch(Exception ex) {
                        }
                        //Util.print("Removing entry " + entry);
                        val.removeElement(entry);
                        return;
                    }
                }
            }
        }
    }


    void removeEntry(String groupname, Address addr) {
        List val;
        AddressEntry entry;


        synchronized(groups) {
            val=(List)groups.get(groupname);
            if(val == null || val.size() == 0)
                return;
            for(Enumeration e2=val.elements(); e2.hasMoreElements();) {
                entry=(AddressEntry)e2.nextElement();
                if(entry.addr.equals(addr)) {
                    try {
                        if(entry.sock != null)
                            entry.sock.close();
                    }
                    catch(Exception ex) {
                    }
                    //Util.print("Removing entry " + entry);
                    val.removeElement(entry);
                    return;
                }
            }
        }
    }


    DataOutputStream findSocket(Address addr) {
        List val;
        AddressEntry entry;

        synchronized(groups) {
            for(Enumeration e=groups.keys(); e.hasMoreElements();) {
                val=(List)groups.get(e.nextElement());
                for(Enumeration e2=val.elements(); e2.hasMoreElements();) {
                    entry=(AddressEntry)e2.nextElement();
                    if(addr.equals(entry.addr))
                        return entry.output;
                }
            }
            return null;
        }
    }


    void sendToAllMembersInGroup(String groupname, byte[] msg) {
        List val;

        synchronized(groups) {
            val=(List)groups.get(groupname);
            if(val == null || val.size() == 0)
                return;
            for(Enumeration e=val.elements(); e.hasMoreElements();) {
                sendToMember(((AddressEntry)e.nextElement()).output, msg);
            }
        }
    }


    void sendToMember(DataOutputStream out, byte[] msg) {
        try {
            if(out != null) {
                out.writeInt(msg.length);
                out.write(msg, 0, msg.length);
            }
        }
        catch(Exception e) {
            Trace.error("Router.sendToMember()", "exception=" + e);
            removeEntry(out); // closes socket
        }
    }


    class AddressEntry {
        Address addr=null;
        Socket sock=null;
        DataOutputStream output=null;


        public AddressEntry(Address addr, Socket sock, DataOutputStream output) {
            this.addr=addr;
            this.sock=sock;
            this.output=output;
        }


        void destroy() {
            if(output != null) {
                try {
                    output.close();
                }
                catch(Exception e) {
                }
                output=null;
            }
            if(sock != null) {
                try {
                    sock.close();
                }
                catch(Exception e) {
                }
                sock=null;
            }
        }

        public boolean equals(Object other) {
            return addr.equals(((AddressEntry)other).addr);
        }

        public String toString() {
            return "addr=" + addr + ", sock=" + sock;
        }
    }


    /** A SocketThread manages one connection to a client. Its main task is message routing. */
    class SocketThread extends Thread {
        Socket sock=null;
        DataInputStream input=null;


        public SocketThread(Socket sock, DataInputStream ois) {
            this.sock=sock;
            input=ois;
        }

        void closeSocket() {
            try {
                if(input != null)
                    input.close();
                if(sock != null)
                    sock.close();
            }
            catch(Exception e) {
            }
        }


        public void run() {
            byte[] buf;
            int len;
            Address dst_addr=null;
            String gname;

            while(true) {
                try {
                    gname=input.readUTF(); // group name
                    len=input.readInt();
                    if(len == 0)
                        dst_addr=null;
                    else {
                        buf=new byte[len];
                        input.readFully(buf, 0, buf.length);  // dest address
                        dst_addr=(Address)Util.objectFromByteBuffer(buf);
                    }

                    len=input.readInt();
                    if(len == 0) {
                        Trace.warn("Router.SocketThread.run()", "received null message");
                        continue;
                    }
                    buf=new byte[len];
                    input.readFully(buf, 0, buf.length);  // message
                    route(dst_addr, gname, buf);
                }
                catch(IOException io_ex) {
                    if(Trace.trace) {
	                    //Trace.info("Router.SocketThread.run()", "client " +
	                    //        sock.getInetAddress().getHostName() + ":" + sock.getPort() +
	                    //        " closed connection; removing it from routing table");
                        Trace.info("Router.SocketThread.run()", "client " +
                        	sock.getInetAddress().getHostAddress() + ":" + sock.getPort() +
                        	" closed connection; removing it from routing table");
                    }
                    removeEntry(sock); // will close socket
                    return;
                }
                catch(Exception e) {
                    Trace.error("Router.SocketThread.run()", "exception=" + e);
                    break;
                }
            }
            closeSocket();
        }

    }


    public static void main(String[] args) throws Exception {
        String arg;
        int port=8080;
        Router router=null;
        InetAddress address=null;
        System.out.println("Router is starting...");
        for(int i=0; i < args.length; i++) {
            arg=args[i];
            if(arg.equals("-help")) {
                System.out.println("Router [-port <port>] [-bindaddress <address>]");
                return;
            }
            else
                if(arg.equals("-port")) {
                    port=new Integer(args[++i]).intValue();
                }
                else
                    if(arg.equals("-bindaddress")) {
                        address=InetAddress.getByName(args[++i]);
                    }

        }

        Trace.init();

        try {
            if(address == null) router=new Router(port); else router=new Router(port, address);
            router.start();
            System.out.println("Router was created at " + new Date());
            System.out.println("Listening on port " + port + " and bound to address " + address);
        }
        catch(Exception e) {
            System.err.println(e);
        }
    }


}
