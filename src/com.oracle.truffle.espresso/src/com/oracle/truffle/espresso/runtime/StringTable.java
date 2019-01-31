/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.runtime;

import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.espresso.impl.ByteString;
import com.oracle.truffle.espresso.impl.ByteString.Constant;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.substitutions.Host;

/**
 * Used to implement String interning.
 */
public final class StringTable { // ByteString<Constant> => StaticObject

    private final EspressoContext context; // per context

    private final ConcurrentHashMap<ByteString<Constant>, String> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StaticObject> interned = new ConcurrentHashMap<>();

    public StringTable(EspressoContext context) {
        this.context = context;
    }

    public StaticObject intern(ByteString<Constant> value) {
        // Weak values?
        return interned.computeIfAbsent(
                        cache.computeIfAbsent(value, StringTable::createStringFromByteString),
                        this::createStringObjectFromString);
    }

    private StaticObject createStringObjectFromString(String value) {
        return context.getMeta().toGuest(value);
    }

    private static String createStringFromByteString(ByteString<Constant> value) {
        return value.toString();
    }

    public StaticObject intern(@Host(String.class) StaticObject stringObject) {
        String hostString = Meta.toHostString(stringObject);
        return interned.computeIfAbsent(hostString, k -> stringObject);
    }
}
