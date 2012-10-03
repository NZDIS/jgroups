// $Id: IpAddress.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups.stack;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.InetAddress;
import java.util.*;
import org.jgroups.util.Util;
import org.jgroups.Address;
import org.jgroups.log.Trace;



/**
 * Network-dependent address (Internet). Generated by the bottommost layer of the protocol
 * stack (UDP). Contains an InetAddress and port.
 * @author Bela Ban
 */
public class IpAddress implements Address {
    private InetAddress       ip_addr=null;
    private int               port=0;
    private byte[] additional_data=null;
    protected static HashMap  sAddrCache=new HashMap();

    static final transient  char[] digits = {
        '0', '1', '2', '3', '4', '5',
        '6', '7', '8', '9'};

    static char[] buf = new char[15];

    // Used only by Externalization
    public IpAddress() {
    }

    public IpAddress(String i, int p) {
        try {
            ip_addr=InetAddress.getByName(i);
        }
        catch(Exception e) {
            Trace.warn("IpAddress.IpAddress()", "failed to get " + i + ":" + p +
                       ", using \"127.0.0.1\", exception: " + Util.printStackTrace(e));
            try {
                ip_addr=InetAddress.getByName("127.0.0.1");
            }
            catch(Exception ex) {
                Trace.warn("IpAddress.IpAddress()", "exception: " + ex);
            }
        }
        port=p;
    }



    public IpAddress(InetAddress i, int p) {
        ip_addr=i; port=p;
    }



    public IpAddress(int port) {
        try {
            ip_addr=InetAddress.getLocalHost();  // get first NIC found (on multi-homed systems)
            this.port=port;
        }
        catch(Exception e) {
            Trace.warn("IpAddress.IpAddress()", "exception: " + e);
        }
    }


    public InetAddress  getIpAddress()               {return ip_addr;}
    public int          getPort()                    {return port;}

    public boolean      isMulticastAddress() {
        return ip_addr != null ? ip_addr.isMulticastAddress() : false;
    }

    /**
     * Returns the additional_data.
     * @return byte[]
     */
    public byte[] getAdditionalData() {
        return additional_data;
    }

    /**
     * Sets the additional_data.
     * @param additional_data The additional_data to set
     */
    public void setAdditionalData(byte[] additional_data) {
        this.additional_data = additional_data;
    }


    /**
     * Establishes an order between 2 addresses. Assumes other contains non-null IpAddress.
     * Excludes channel_name from comparison.
     * @return 0 for equality, value less than 0 if smaller, greater than 0 if greater.
     */
    public int compare(IpAddress other) {
        return compareTo(other);
    }


    /**
     * implements the java.lang.Comparable interface
     * @see java.lang.Comparable
     * @param o - the Object to be compared
     * @return a negative integer, zero, or a positive integer as this object is less than,
     *         equal to, or greater than the specified object.
     * @exception java.lang.ClassCastException - if the specified object's type prevents it
     *            from being compared to this Object.
     */
    public int compareTo(Object o) {
      int   h1, h2, rc;

      if ((o == null) || !(o instanceof IpAddress))
          throw new ClassCastException("IpAddress.compareTo(): comparison between different classes");
      IpAddress other = (IpAddress) o;
      if(ip_addr == null)
          if (other.ip_addr == null) return port < other.port ? -1 : (port > other.port ? 1 : 0);
          else return -1;

      h1=ip_addr.hashCode();
      h2=other.ip_addr.hashCode();
      rc=h1 < h2? -1 : h1 > h2? 1 : 0;
      return rc != 0 ? rc : port < other.port ? -1 : (port > other.port ? 1 : 0);
    }



    public boolean equals(Object obj) {
        if(obj == null) return false;
        return compareTo(obj) == 0 ? true : false;
    }




    public int hashCode() {
        int retval=ip_addr != null ? ip_addr.hashCode() + port : port;
        return retval;
    }




    public String toString() {
        StringBuffer sb=new StringBuffer();

        if(ip_addr == null)
            sb.append("<null>");
        else {
            //if(ip_addr.isMulticastAddress())
                sb.append(ip_addr.getHostAddress());
            //else
                //appendShortName(ip_addr.getHostName(), sb);
        }
        sb.append(":" + port);
        if(additional_data != null)
            sb.append(" (additional data: ").append(additional_data.length).append(" bytes)");
        return sb.toString();
    }





    /**
     * Input: "daddy.nms.fnc.fujitsu.com", output: "daddy". Appends result to string buffer 'sb'.
     * @param hostname The hostname in long form. Guaranteed not to be null
     * @param sb The string buffer to which the result is to be appended
     */
    private void appendShortName(String hostname, StringBuffer sb) {
        int  index=hostname.indexOf('.');

        if(hostname == null) return;
        if(index > 0 && !Character.isDigit(hostname.charAt(0)))
            sb.append(hostname.substring(0, index));
        else
            sb.append(hostname);
    }


    /**
     * Converts 4 byte address representation into a char array
     * of length 7-15, depending on the actual address, i.e XXX.XXX.XXX.XXX
     * and returns a String representation of that address.
     *
     * @param address 4 byte array representing address
     *
     */
    private static final String addressToString(byte[] address) {
        int q,r = 0;
        int charPos = 15;
        char dot = '.';

        int i = address[3] & 0xFF;
        for(;;) {
            q = (i * 52429) >>> (19);
            r = i - ((q << 3) + (q << 1));
            buf[--charPos] = digits[r];
            i = q;
            if (i == 0) break;
        }
        buf[--charPos] = dot;
        i = address[2] & 0xFF;
        for (;;) {
            q = (i * 52429) >>> (19);
            r = i - ((q << 3) + (q << 1));
            buf[--charPos] = digits[r];
            i = q;
            if (i == 0) break;
        }
        buf[--charPos] = dot;

        i = address[1] & 0xFF;
        for (;;) {
            q = (i * 52429) >>> (19);
            r = i - ((q << 3) + (q << 1));
            buf[--charPos] = digits[r];
            i = q;
            if (i == 0) break;
        }

        buf[--charPos] = dot;
        i = address[0] & 0xFF;

        for (;;) {
            q = (i * 52429) >>> (19);
            r = i - ((q << 3) + (q << 1));
            buf[--charPos] = digits[r];
            i = q;
            if (i == 0) break;
        }
        return new String(buf, charPos, 15 - charPos);
    }


    public void writeExternal(ObjectOutput out) throws IOException {
        byte[] address = ip_addr.getAddress();
        out.write(address);
        out.writeInt(port);
        if(additional_data != null) {
            out.writeInt(additional_data.length);
            out.write(additional_data, 0, additional_data.length);
        }
        else
            out.writeInt(0);
    }




    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        int len=0;
        //read the four bytes
        byte[] a = new byte[4];
        //in theory readFully(byte[]) should be faster
        //than read(byte[]) since latter reads
        // 4 bytes one at a time
        in.readFully(a);
        //then read the port
        port = in.readInt();
        //look up an instance in the cache
        this.ip_addr = getIpAddress(a);
        len=in.readInt();
        if(len > 0) {
            additional_data=new byte[len];
            in.readFully(additional_data, 0, additional_data.length);
        }
    }

    public Object clone() throws CloneNotSupportedException {
        IpAddress ret=new IpAddress(ip_addr, port);
        if(additional_data != null) {
            ret.additional_data=new byte[additional_data.length];
            System.arraycopy(additional_data, 0, ret.additional_data, 0, additional_data.length);
        }

        return ret;
    }


    protected static InetAddress getIpAddress(byte[] addr) {
        try {
            HashKey key = new HashKey(addr);
            InetAddress result;

            synchronized(sAddrCache) {
                result=(InetAddress)sAddrCache.get(key);
                if(result == null) {
                    result = java.net.InetAddress.getByName(addressToString(addr));
                    sAddrCache.put(key,result);
                }
            }
            return result;
        }
        catch (Exception x) {
            x.printStackTrace();
            Trace.error("IpAddress",x.getMessage());
        }
        return null;

    }

    static class HashKey {
        private byte[] mIpAddress;

        public HashKey(byte[] ipaddress) {
            if (ipaddress == null)
                mIpAddress = new byte[0];
            else
                mIpAddress = ipaddress;
        }

        public int hashCode() {
            if(mIpAddress.length > 0)
                return (int)mIpAddress[0];
            else
                return 0;
        }

        public byte[] getIpBytes() {
            return mIpAddress;
        }

        public boolean equals(Object o) {
            if (o != null && o instanceof HashKey) {
                byte[] other = ((HashKey)o).getIpBytes();
                if ( other.length != mIpAddress.length )
                    return false;
                boolean result = true;
                for ( int i=0; i<other.length && result; i++ )
                    result = result & (other[i] == mIpAddress[i]);
                return result;
            }
            else
                return false;
        }
    }



}
