package com.oracle.truffle.espresso.impl;

import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.impl.ByteString.Name;
import com.oracle.truffle.espresso.impl.ByteString.Type;

public final class LinkedField {
    private final ParserField parserField;
    private final LinkedKlass holderLinkedKlass;

    protected ConstantPool getConstantPool() {
        return holderLinkedKlass.getConstantPool();
    }

    private final int slot; // already computed here

    public LinkedField(ParserField parserField, LinkedKlass holderLinkedKlass, int slot) {
        this.parserField = parserField;
        this.holderLinkedKlass = holderLinkedKlass;
        this.slot = slot;
    }

    public ByteString<Type> getType() {
        return parserField.getType();
    }

    public ByteString<Name> getName() {
        return parserField.getName();
    }

    public int getSlot() {
        return slot;
    }

    public int getFlags() {
        return parserField.getFlags();
    }
}
