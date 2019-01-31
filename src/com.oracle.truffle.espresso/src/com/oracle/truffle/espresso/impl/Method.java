package com.oracle.truffle.espresso.impl;

import java.lang.reflect.Modifier;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.espresso.Utils;
import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.classfile.ExceptionsAttribute;
import com.oracle.truffle.espresso.descriptors.Signatures;
import com.oracle.truffle.espresso.impl.ByteString.Name;
import com.oracle.truffle.espresso.impl.ByteString.Signature;
import com.oracle.truffle.espresso.impl.ByteString.Type;
import com.oracle.truffle.espresso.jni.Mangle;
import com.oracle.truffle.espresso.jni.NativeLibrary;
import com.oracle.truffle.espresso.meta.ExceptionHandler;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.LineNumberTable;
import com.oracle.truffle.espresso.meta.LocalVariableTable;
import com.oracle.truffle.espresso.meta.ModifiersProvider;
import com.oracle.truffle.espresso.nodes.BytecodeNode;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.nodes.JniNativeNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.runtime.StaticObjectImpl;
import com.oracle.truffle.nfi.types.NativeSimpleType;

public final class Method implements ModifiersProvider, ContextAccess {

    public static final ByteString<Name> INIT = ByteString.fromJavaString("<init>");
    public static final ByteString<Name> CLINIT = ByteString.fromJavaString("<clinit>");

    public static final Method[] EMPTY_ARRAY = new Method[0];
    private static final ByteString<Signature> CLINIT_SIGNATURE = ByteString.fromJavaString("()V");

    private final LinkedMethod linkedMethod;
    private final ConstantPool pool;

    private final ObjectKlass declaringKlass;

    private final ByteString<Name> name;

    private final ByteString<Signature> rawSignature;

    @CompilationFinal(dimensions = 1) //
    private final ByteString<Type>[] parsedSignature;

    @CompilationFinal(dimensions = 1) private final byte[] code;

    private final int maxStackSize;
    private final int maxLocals;
    private final ExceptionHandler[] exceptionHandlers;
    private final LineNumberTable lineNumberTable;
    private final LocalVariableTable localVariableTable;
    private final ExceptionsAttribute exceptionsAttribute;

    @CompilationFinal //
    private CallTarget callTarget;

    @CompilationFinal(dimensions = 1) //
    private Klass[] checkedExceptions;

    // can have a different constant pool than it's declaring class
    public ConstantPool getConstantPool() {
        return pool;
    }

    public Klass getDeclaringKlass() {
        return declaringKlass;
    }

    public ByteString<Name> getName() {
        return name;
    }

    public ByteString<Signature> getRawSignature() {
        return rawSignature;
    }

    public ByteString<Type>[] getParsedSignature() {
        return parsedSignature;
    }

    Method(ObjectKlass declaringKlass, LinkedMethod linkedMethod, byte[] code, int maxStackSize, int maxLocals,
                    ExceptionHandler[] exceptionHandlers,
                    LineNumberTable lineNumberTable,
                    LocalVariableTable localVariableTable,
                    ExceptionsAttribute exceptionsAttribute) {

        this.declaringKlass = declaringKlass;
        this.name = linkedMethod.getName();
        this.rawSignature = linkedMethod.getSignature();
        this.parsedSignature = declaringKlass.getSignatures().make(linkedMethod.getSignature());
        this.linkedMethod = linkedMethod;
        this.code = code;
        this.maxStackSize = maxStackSize;
        this.maxLocals = maxLocals;
        this.exceptionHandlers = exceptionHandlers;
        this.lineNumberTable = lineNumberTable;
        this.localVariableTable = localVariableTable;
        this.exceptionsAttribute = exceptionsAttribute;
    }

    @Override
    public EspressoContext getContext() {
        return declaringKlass.getContext();
    }

    public byte[] getCode() {
        return code;
    }

    public int getCodeSize() {
        return code != null ? code.length : 0;
    }

    public int getMaxLocals() {
        return maxLocals;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }

    public ExceptionHandler[] getExceptionHandlers() {
        return exceptionHandlers;
    }

    private static String buildJniNativeSignature(Method method) {
        // Prepend JNIEnv*.
        StringBuilder sb = new StringBuilder("(").append(NativeSimpleType.SINT64);
        final ByteString<Type>[] signature = method.getParsedSignature();

        // Receiver for instance methods, class for static methods.
        sb.append(", ").append(NativeSimpleType.NULLABLE);

        int argCount = Signatures.parameterCount(signature, false);
        for (int i = 0; i < argCount; ++i) {
            sb.append(", ").append(Utils.kindToType(Signatures.parameterKind(signature, i), true));
        }

        sb.append("): ").append(Utils.kindToType(Signatures.returnKind(signature), false));

        return sb.toString();
    }

    private static TruffleObject bind(TruffleObject library, Method m, String mangledName) throws UnknownIdentifierException {
        String signature = buildJniNativeSignature(m);
        return NativeLibrary.lookupAndBind(library, mangledName, signature);
    }

    private static TruffleObject bind(TruffleObject symbol, Method m) {
        String signature = buildJniNativeSignature(m);
        return NativeLibrary.bind(symbol, signature);
    }

    @TruffleBoundary
    public CallTarget getCallTarget() {
        // TODO(peterssen): Make lazy call target thread-safe.
        if (callTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            // TODO(peterssen): Rethink method substitution logic.
            EspressoRootNode redirectedMethod = getInterpreterToVM().getSubstitution(this);
            if (redirectedMethod != null) {
                callTarget = Truffle.getRuntime().createCallTarget(redirectedMethod);
            } else {
                if (this.isNative()) {
                    // Bind native method.
                    // System.err.println("Linking native method: " +
                    // meta(this).getDeclaringClass().getName() + "#" + getName() + " " +
                    // getSignature());

                    // If the loader is null we have a system class, so we attempt a lookup in
                    // the native Java library.
                    if (StaticObject.isNull(getDeclaringKlass().getClassLoader())) {
                        // Look in libjava
                        for (boolean withSignature : new boolean[]{false, true}) {
                            String mangledName = Mangle.mangleMethod(this, withSignature);

                            try {
                                TruffleObject nativeMethod = bind(getVM().getJavaLibrary(), this, mangledName);
                                callTarget = Truffle.getRuntime().createCallTarget(new JniNativeNode(getLanguage(), nativeMethod, this));
                                return callTarget;
                            } catch (UnknownIdentifierException e) {
                                // native method not found in libjava, safe to ignore
                            }
                        }
                    }

                    Method findNative = getMeta().knownKlass(ClassLoader.class).staticMethod("findNative", long.class, ClassLoader.class, String.class);

                    // Lookup the short name first, otherwise lookup the long name (with signature).
                    callTarget = lookupJniCallTarget(findNative, false);
                    if (callTarget == null) {
                        callTarget = lookupJniCallTarget(findNative, true);
                    }

                    // TODO(peterssen): Search JNI methods with OS prefix/suffix
                    // (print_jni_name_suffix_on ...)

                    if (callTarget == null) {
                        System.err.println("Failed to link native method: " + getDeclaringKlass().getType() + "." + getName() + " -> " + getRawSignature());
                        throw getMeta().throwEx(UnsatisfiedLinkError.class);
                    }
                } else {
                    callTarget = Truffle.getRuntime().createCallTarget(new BytecodeNode(getLanguage(), this, getInterpreterToVM()));
                }
            }
        }

        return callTarget;
    }

    private CallTarget lookupJniCallTarget(Method findNative, boolean fullSignature) {
        String mangledName = Mangle.mangleMethod(this, fullSignature);
        long handle = (long) findNative.invoke(getDeclaringKlass().getClassLoader(), mangledName);
        if (handle == 0) { // not found
            return null;
        }
        TruffleObject symbol = getVM().getFunction(handle);
        TruffleObject nativeMethod = bind(symbol, this);
        return Truffle.getRuntime().createCallTarget(new JniNativeNode(getLanguage(), nativeMethod, this));
    }

    public boolean isConstructor() {
        assert Signatures.returnKind(getParsedSignature()) == JavaKind.Void;
        assert !isStatic();
        return INIT.equals(getName());
    }

    public boolean isDefault() {
        if (isConstructor()) {
            return false;
        }
        // Copied from java.lang.Method.isDefault()
        int mask = Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC;
        return ((getModifiers() & mask) == Modifier.PUBLIC) && getDeclaringKlass().isInterface();
    }

    public Klass[] getCheckedExceptions() {
        if (checkedExceptions == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (exceptionsAttribute == null) {
                checkedExceptions = Klass.EMPTY_ARRAY;
                return checkedExceptions;
            }
            final int[] entries = exceptionsAttribute.getCheckedExceptionsCPI();
            checkedExceptions = new Klass[entries.length];
            for (int i = 0; i < entries.length; ++i) {
                checkedExceptions[i] = getConstantPool().classAt(entries[i]).resolve(getConstantPool(), entries[i]);
            }
        }
        return checkedExceptions;
    }

    public boolean isFinal() {
        return ModifiersProvider.super.isFinalFlagSet();
    }

    /**
     * Returns the LineNumberTable of this method or null if this method does not have a line
     * numbers table.
     */
    public LineNumberTable getLineNumberTable() {
        return lineNumberTable;
    }

    /**
     * Returns the local variable table of this method or null if this method does not have a local
     * variable table.
     */
    public LocalVariableTable getLocalVariableTable() {
        return localVariableTable;
    }

    /**
     * Checks whether the method has bytecodes associated with it. Methods without bytecodes are
     * either abstract or native methods.
     *
     * @return whether the definition of this method is Java bytecodes
     */
    public boolean hasBytecodes() {
        return isConcrete() && !isNative();
    }

    /**
     * Checks whether the method has a receiver parameter - i.e., whether it is not static.
     *
     * @return whether the method has a receiver parameter
     */
    public boolean hasReceiver() {
        return !isStatic();
    }

    /**
     * Determines if this method is {@link java.lang.Object#Object()}.
     */
    public boolean isJavaLangObjectInit() {
        return getDeclaringKlass().isJavaLangObject() && getName().equals("<init>");
    }

    // region Meta.Method

    /**
     * Invoke guest method, parameters and return value are converted to host world. Primitives,
     * primitive arrays are shared, and are passed verbatim, conversions are provided for String and
     * StaticObject.NULL/null. There's no parameter casting based on the method's signature,
     * widening nor narrowing.
     */
    @TruffleBoundary
    public Object invoke(Object self, Object... args) {
        assert args.length == Signatures.parameterCount(getParsedSignature(), false);
        assert !isStatic() || ((StaticObjectImpl) self).isStatic();

        final Object[] filteredArgs;
        if (isStatic()) {
            filteredArgs = new Object[args.length];
            for (int i = 0; i < filteredArgs.length; ++i) {
                filteredArgs[i] = getMeta().toGuestBoxed(args[i]);
            }
        } else {
            filteredArgs = new Object[args.length + 1];
            filteredArgs[0] = getMeta().toGuestBoxed(self);
            for (int i = 1; i < filteredArgs.length; ++i) {
                filteredArgs[i] = getMeta().toGuestBoxed(args[i - 1]);
            }
        }
        return getMeta().toHostBoxed(getCallTarget().call(filteredArgs));
    }

    /**
     * Invoke a guest method without parameter/return type conversion. There's no parameter casting
     * based on the method's signature, widening nor narrowing.
     */
    @TruffleBoundary
    public Object invokeDirect(Object self, Object... args) {
        if (isStatic()) {
            assert args.length == Signatures.parameterCount(getParsedSignature(), false);
            return getCallTarget().call(args);
        } else {
            assert args.length + 1 /* self */ == Signatures.parameterCount(getParsedSignature(), !isStatic());
            Object[] fullArgs = new Object[args.length + 1];
            System.arraycopy(args, 0, fullArgs, 1, args.length);
            fullArgs[0] = self;
            return getCallTarget().call(fullArgs);
        }
    }

    public final boolean isClassInitializer() {
        assert Signatures.resultKind(getParsedSignature()) == JavaKind.Void;
        assert isStatic();
        assert Signatures.parameterCount(getParsedSignature(), false) == 0;
        assert !CLINIT.equals(getName()) || CLINIT_SIGNATURE.equals(getRawSignature());
        return CLINIT.equals(getName());
    }

    @Override
    public int getModifiers() {
        return linkedMethod.getFlags() & Modifier.methodModifiers();
    }

    @Override
    public String toString() {
        return "EspressoMethod<" + getDeclaringKlass().getType() + "." + getName() + " -> " + getRawSignature() + ">";
    }

    public final JavaKind getReturnKind() {
        return Signatures.returnKind(getParsedSignature());
    }
}
