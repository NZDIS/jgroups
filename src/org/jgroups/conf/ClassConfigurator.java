// $Id: ClassConfigurator.java,v 1.1 2008/09/03 04:24:45 commerce\wuti7102 Exp $

package org.jgroups.conf;


import org.jgroups.log.Trace;
import org.jgroups.util.Util;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * This class will be replaced with the class that read info
 * from the magic number configurator that reads info from the xml file.<br>
 * The name and the relative path of the magic number map file can be specified
 * as value of the property <code>org.jgroups.conf.magicNumberFile</code>.
 * It must be relative to one of the classpath elements, to allow the
 * classloader to locate the file. If a value is not specified,
 * <code>MagicNumberReader.MAGIC_NUMBER_FILE</code> is used, which defaults
 * to "jg-magic-map.xml".
 * @see org.jgroups.conf.MagicNumberReader
 *
 * @author Filip Hanik
 * @author Bela Ban
 */
public class ClassConfigurator
{
    static ClassConfigurator instance=null;

    //this is where we store magic numbers
    private Map classMap = new HashMap(); // key=Class, value=magic number
    private Map magicMap = new TreeMap(); // key=magic number, value=Class




    private ClassConfigurator(boolean init) {
        //populate the map
        if (!init) return;

    }


    public static ClassConfigurator getInstance() {
	return instance != null? instance : (instance=new ClassConfigurator(true));
    }


    /**
     * Returns a class for a magic number.
     * Returns null if no class is found
     * @param magic the magic number that maps to the class
     * @return a Class object that represents a class that implements java.io.Externalizable
     */
    public Class get(int magic)
    {
	    return (Class)magicMap.get(new Integer(magic));
    }

    /**
         * Loads and returns the class from the class name
         * @param clazzname a fully classified class name to be loaded
         * @return a Class object that represents a class that implements java.io.Externalizable
         */
    public Class get(String clazzname)
    {
        try
        {
            return ClassConfigurator.class.getClassLoader().loadClass(clazzname);
        }
        catch ( Exception x )
        {
            Trace.error("ClassConfigurator",Trace.getStackTrace(x));
        }
        return null;
    }

    /**
     * Returns the magic number for the class.
     * @param clazz a class object that we want the magic number for
     * @return the magic number for a class, -1 if no mapping is available
     */
//    public int getMagicNumber(Class clazz)
//    {
//        Integer i = (Integer)classMap.get(clazz);
//        if ( i == null )
//            return -1;
//        else
//            return i.intValue();
//    }


    public String toString()
    {
    	return printMagicMap();
    }

    public String printMagicMap()
    {
        StringBuffer sb=new StringBuffer();
        Map.Entry    entry;

        for(Iterator it=magicMap.entrySet().iterator(); it.hasNext();) {
            entry=(Map.Entry)it.next();
            sb.append(entry.getKey()).append(":\t").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    public String printClassMap()
    {
        StringBuffer sb=new StringBuffer();
        Map.Entry    entry;

        for(Iterator it=classMap.entrySet().iterator(); it.hasNext();) {
            entry=(Map.Entry)it.next();
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }



    /* --------------------------------- Private methods ------------------------------------ */

    /* ------------------------------ End of Pivate methods --------------------------------- */
    public static void main(String[] args)
        throws Exception
    {
        Trace.init();
        ClassConfigurator test = getInstance();
        System.out.println("\n" + test.printMagicMap());
    }
}
