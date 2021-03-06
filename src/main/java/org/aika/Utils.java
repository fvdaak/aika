/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aika;


import org.aika.corpus.InterprNode;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Comparator;

/**
 *
 * @author Lukas Molzberger
 */
public class Utils {


    public static <T> boolean contains(T[] in, T x, Comparator<T> c) {
        for(T y: in) {
            if(c.compare(x, y) == 0) return true;
        }
        return false;
    }


    public static <T> T[] addToArray(T[] in, T n) {
        T[] r = Arrays.copyOf(in, in.length + 1);
        r[in.length] = n;
        return r;
    }


    public static <T> T[] removeToArray(T[] in, T n) {
        T[] r = (T[]) Array.newInstance(n.getClass(), in.length - 1);
        int i = 0;
        for(T x: in) {
            if(x != n) {
                r[i++] = x;
            }
        }

        return r;
    }


    public static boolean compareNullSafe(Integer a, Integer b) {
        if(a == null || b == null) return true;
        return a >= b;
    }


    public static int compareInteger(Integer a, Integer b) {
        if(a == null && b != null) return -1;
        if(a != null && b == null) return 1;
        if(a != null && b != null) {
            return Integer.compare(a, b);
        } else return 0;
    }


    public static double round(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }


    public static Integer nullSafeAdd(Integer a, boolean fa, Integer b, boolean fb) {
        if(b == null) return fa ? a : null;
        if(a == null) return fb ? b : null;
        else return a + b;
    }


    public static Integer nullSafeSub(Integer a, boolean fa, Integer b, boolean fb) {
        if(b == null) return fa ? a : null;
        if(a == null) return fb ? b : null;
        return a - b;
    }


    public static Integer nullSafeMin(Integer a, Integer b) {
        if(a == null) return b;
        if(b == null) return a;
        return Math.min(a, b);
    }

    public static Integer nullSafeMax(Integer a, Integer b) {
        if(a == null) return b;
        if(b == null) return a;
        return Math.max(a, b);
    }
}
