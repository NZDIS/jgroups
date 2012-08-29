// $Id: MethodLookup.java,v 1.1 2008/09/03 04:24:38 commerce\wuti7102 Exp $

package org.jgroups.blocks;

import java.lang.reflect.Method;
import java.util.Vector;

public interface MethodLookup {
    Method findMethod(Class target_class, String method_name, Vector args) throws Exception;
}
