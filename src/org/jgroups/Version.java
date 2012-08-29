package org.jgroups;


public class Version {
    public static String version="2.2.1";
    public static byte[] version_id={'0', '2', '2', '1'};


    public static void main(String[] args) {
        System.out.println("\nVersion: \t" + version);
        System.out.println("History: \t(see doc/history.txt for details)\n");
    }


    public static String printVersionId(byte[] v, int len) {
        StringBuffer sb=new StringBuffer();
        if(v != null) {
            if(len <= 0)
                len=v.length;
            for(int i=0; i < len; i++)
                sb.append((char)v[i]);
        }
        return sb.toString();
    }

       public static String printVersionId(byte[] v) {
        StringBuffer sb=new StringBuffer();
        if(v != null) {
            for(int i=0; i < v.length; i++)
                sb.append((char)v[i]);
        }
        return sb.toString();
    }

    /**
     * Don't use this method; used by unit testing only.
     * @param v
     */
    public static void setVersion(byte[] v) {
        version_id=v;
    }

    public static boolean compareTo(byte[] v) {
        if(v == null)
            return false;
        if(v.length < version_id.length)
            return false;
        for(int i=0; i < version_id.length; i++) {
            if(version_id[i] != v[i])
                return false;
        }
        return true;
    }

    public static int getLength() {
        return version_id.length;
    }

}