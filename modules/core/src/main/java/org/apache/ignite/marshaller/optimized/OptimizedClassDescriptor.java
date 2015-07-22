/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.marshaller.optimized;

import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.marshaller.*;
import sun.misc.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.reflect.Modifier.*;
import static org.apache.ignite.marshaller.optimized.OptimizedMarshallerUtils.*;
import static org.apache.ignite.marshaller.optimized.OptimizedMarshallerUtils.ENUM;

/**
 * Class descriptor.
 */
public class OptimizedClassDescriptor {
    /** Unsafe. */
    private static final Unsafe UNSAFE = GridUnsafe.unsafe();

    /** Class. */
    private final Class<?> cls;

    /** Context. */
    private final MarshallerContext ctx;

    /** */
    private ConcurrentMap<Class, OptimizedClassDescriptor> clsMap;

    /** ID mapper. */
    private final OptimizedMarshallerIdMapper mapper;

    /** Indexing manager. */
    private final OptimizedMarshallerIndexingHandler idxHandler;

    /** Class name. */
    private final String name;

    /** Type ID. */
    private final int typeId;

    /** Short ID. */
    private final short checksum;

    /** Class type. */
    private int type;

    /** Primitive flag. */
    private boolean isPrimitive;

    /** Enum flag. */
    private boolean isEnum;

    /** Serializable flag. */
    private boolean isSerial;

    /** Excluded flag. */
    private boolean excluded;

    /** {@code True} if descriptor is for {@link Class}. */
    private boolean isCls;

    /** Enumeration values. */
    private Object[] enumVals;

    /** Constructor. */
    private Constructor<?> constructor;

    /** Fields. */
    private Fields fields;

    /** {@code writeObject} methods. */
    private List<Method> writeObjMtds;

    /** {@code writeReplace} method. */
    private Method writeReplaceMtd;

    /** {@code readObject} methods. */
    private List<Method> readObjMtds;

    /** {@code readResolve} method. */
    private Method readResolveMtd;

    /** Defaults field offset. */
    private long dfltsFieldOff;

    /** Load factor field offset. */
    private long loadFactorFieldOff;

    /** Access order field offset. */
    private long accessOrderFieldOff;

    /**
     * Creates descriptor for class.
     *
     * @param typeId Type ID.
     * @param clsMap Class descriptors by class map.
     * @param cls Class.
     * @param ctx Context.
     * @param mapper ID mapper.
     * @param idxHandler Fields indexing manager.
     * @throws IOException In case of error.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    OptimizedClassDescriptor(Class<?> cls,
        int typeId,
        ConcurrentMap<Class, OptimizedClassDescriptor> clsMap,
        MarshallerContext ctx,
        OptimizedMarshallerIdMapper mapper,
        OptimizedMarshallerIndexingHandler idxHandler)
        throws IOException {
        this.cls = cls;
        this.typeId = typeId;
        this.clsMap = clsMap;
        this.ctx = ctx;
        this.mapper = mapper;
        this.idxHandler = idxHandler;

        name = cls.getName();

        excluded = MarshallerExclusions.isExcluded(cls);

        if (!excluded) {
            Class<?> parent;

            if (cls == byte.class || cls == Byte.class) {
                type = BYTE;

                isPrimitive = true;
            }
            else if (cls == short.class || cls == Short.class) {
                type = SHORT;

                isPrimitive = true;
            }
            else if (cls == int.class || cls == Integer.class) {
                type = INT;

                isPrimitive = true;
            }
            else if (cls == long.class || cls == Long.class) {
                type = LONG;

                isPrimitive = true;
            }
            else if (cls == float.class || cls == Float.class) {
                type = FLOAT;

                isPrimitive = true;
            }
            else if (cls == double.class || cls == Double.class) {
                type = DOUBLE;

                isPrimitive = true;
            }
            else if (cls == char.class || cls == Character.class) {
                type = CHAR;

                isPrimitive = true;
            }
            else if (cls == boolean.class || cls == Boolean.class) {
                type = BOOLEAN;

                isPrimitive = true;
            }
            else if (cls == byte[].class)
                type = BYTE_ARR;
            else if (cls == short[].class)
                type = SHORT_ARR;
            else if (cls == int[].class)
                type = INT_ARR;
            else if (cls == long[].class)
                type = LONG_ARR;
            else if (cls == float[].class)
                type = FLOAT_ARR;
            else if (cls == double[].class)
                type = DOUBLE_ARR;
            else if (cls == char[].class)
                type = CHAR_ARR;
            else if (cls == boolean[].class)
                type = BOOLEAN_ARR;
            else if (cls.isArray())
                type = OBJ_ARR;
            else if (cls == String.class)
                type = STR;
            else if (cls.isEnum()) {
                type = ENUM;

                isEnum = true;
                enumVals = cls.getEnumConstants();
            }
            // Support for enum constants, based on anonymous children classes.
            else if ((parent = cls.getSuperclass()) != null && parent.isEnum()) {
                type = ENUM;

                isEnum = true;
                enumVals = parent.getEnumConstants();
            }
            else if (cls == UUID.class)
                type = UUID;
            else if (cls == Properties.class) {
                type = PROPS;

                try {
                    dfltsFieldOff = UNSAFE.objectFieldOffset(Properties.class.getDeclaredField("defaults"));
                }
                catch (NoSuchFieldException e) {
                    throw new IOException(e);
                }
            }
            else if (cls == ArrayList.class)
                type = ARRAY_LIST;
            else if (cls == HashMap.class) {
                type = HASH_MAP;

                try {
                    loadFactorFieldOff = UNSAFE.objectFieldOffset(HashMap.class.getDeclaredField("loadFactor"));
                }
                catch (NoSuchFieldException e) {
                    throw new IOException(e);
                }
            }
            else if (cls == HashSet.class) {
                type = HASH_SET;

                try {
                    loadFactorFieldOff = UNSAFE.objectFieldOffset(HashMap.class.getDeclaredField("loadFactor"));
                }
                catch (NoSuchFieldException e) {
                    throw new IOException(e);
                }
            }
            else if (cls == LinkedList.class)
                type = LINKED_LIST;
            else if (cls == LinkedHashMap.class) {
                type = LINKED_HASH_MAP;

                try {
                    loadFactorFieldOff = UNSAFE.objectFieldOffset(HashMap.class.getDeclaredField("loadFactor"));
                    accessOrderFieldOff = UNSAFE.objectFieldOffset(LinkedHashMap.class.getDeclaredField("accessOrder"));
                }
                catch (NoSuchFieldException e) {
                    throw new IOException(e);
                }
            }
            else if (cls == LinkedHashSet.class) {
                type = LINKED_HASH_SET;

                try {
                    loadFactorFieldOff = UNSAFE.objectFieldOffset(HashMap.class.getDeclaredField("loadFactor"));
                }
                catch (NoSuchFieldException e) {
                    throw new IOException(e);
                }
            }
            else if (cls == Date.class)
                type = DATE;
            else if (cls == Class.class) {
                type = CLS;

                isCls = true;
            }
            else {
                Class<?> c = cls;

                while ((writeReplaceMtd == null || readResolveMtd == null) && c != null && !c.equals(Object.class)) {
                    if (writeReplaceMtd == null) {
                        try {
                            writeReplaceMtd = c.getDeclaredMethod("writeReplace");

                            if (!isStatic(writeReplaceMtd.getModifiers()) &&
                                !(isPrivate(writeReplaceMtd.getModifiers()) && c != cls) &&
                                writeReplaceMtd.getReturnType().equals(Object.class))
                                writeReplaceMtd.setAccessible(true);
                            else
                                // Set method back to null if it has incorrect signature.
                                writeReplaceMtd = null;
                        }
                        catch (NoSuchMethodException ignored) {
                            // No-op.
                        }
                    }

                    if (readResolveMtd == null) {
                        try {
                            readResolveMtd = c.getDeclaredMethod("readResolve");

                            if (!isStatic(readResolveMtd.getModifiers()) &&
                                !(isPrivate(readResolveMtd.getModifiers()) && c != cls) &&
                                readResolveMtd.getReturnType().equals(Object.class))
                                readResolveMtd.setAccessible(true);
                            else
                                // Set method back to null if it has incorrect signature.
                                readResolveMtd = null;
                        }
                        catch (NoSuchMethodException ignored) {
                            // No-op.
                        }
                    }

                    c = c.getSuperclass();
                }

                if (Externalizable.class.isAssignableFrom(cls)) {
                    type = EXTERNALIZABLE;

                    try {
                        constructor = !Modifier.isStatic(cls.getModifiers()) && cls.getDeclaringClass() != null ?
                            cls.getDeclaredConstructor(cls.getDeclaringClass()) :
                            cls.getDeclaredConstructor();

                        constructor.setAccessible(true);
                    }
                    catch (NoSuchMethodException e) {
                        throw new IOException("Externalizable class doesn't have default constructor: " + cls, e);
                    }
                }
                else if (OptimizedMarshalAware.class.isAssignableFrom(cls)) {
                    type = MARSHAL_AWARE;

                    try {
                        constructor = !Modifier.isStatic(cls.getModifiers()) && cls.getDeclaringClass() != null ?
                            cls.getDeclaredConstructor(cls.getDeclaringClass()) :
                            cls.getDeclaredConstructor();

                        constructor.setAccessible(true);
                    }
                    catch (NoSuchMethodException e) {
                        throw new IOException("OptimizedMarshalAware class doesn't have default constructor: " + cls,
                            e);
                    }
                }
                else {
                    type = SERIALIZABLE;

                    isSerial = Serializable.class.isAssignableFrom(cls);

                    writeObjMtds = new ArrayList<>();
                    readObjMtds = new ArrayList<>();

                    List<ClassFields> fields = new ArrayList<>();
                    Set<String> fieldsSet = new HashSet<>();

                    boolean fieldsIndexingSupported = true;

                    for (c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
                        Method mtd;

                        try {
                            mtd = c.getDeclaredMethod("writeObject", ObjectOutputStream.class);

                            int mod = mtd.getModifiers();

                            if (!isStatic(mod) && isPrivate(mod) && mtd.getReturnType() == Void.TYPE) {
                                mtd.setAccessible(true);
                                fieldsIndexingSupported = false;
                            }
                            else
                                // Set method back to null if it has incorrect signature.
                                mtd = null;
                        }
                        catch (NoSuchMethodException ignored) {
                            mtd = null;
                        }

                        writeObjMtds.add(mtd);

                        try {
                            mtd = c.getDeclaredMethod("readObject", ObjectInputStream.class);

                            int mod = mtd.getModifiers();

                            if (!isStatic(mod) && isPrivate(mod) && mtd.getReturnType() == Void.TYPE) {
                                mtd.setAccessible(true);
                                fieldsIndexingSupported = false;
                            }
                            else
                                // Set method back to null if it has incorrect signature.
                                mtd = null;
                        }
                        catch (NoSuchMethodException ignored) {
                            mtd = null;
                        }

                        readObjMtds.add(mtd);

                        Field[] clsFields0 = c.getDeclaredFields();

                        Map<String, Field> fieldNames = new HashMap<>();

                        for (Field f : clsFields0) {
                            fieldNames.put(f.getName(), f);

                            // Check for fields duplicate names in classes hierarchy
                            if (!fieldsSet.add(f.getName()))
                                fieldsIndexingSupported = false;
                        }

                        List<FieldInfo> clsFields = new ArrayList<>(clsFields0.length);

                        boolean hasSerialPersistentFields  = false;

                        try {
                            Field serFieldsDesc = c.getDeclaredField("serialPersistentFields");

                            int mod = serFieldsDesc.getModifiers();

                            if (serFieldsDesc.getType() == ObjectStreamField[].class &&
                                isPrivate(mod) && isStatic(mod) && isFinal(mod)) {
                                hasSerialPersistentFields = true;

                                serFieldsDesc.setAccessible(true);

                                ObjectStreamField[] serFields = (ObjectStreamField[]) serFieldsDesc.get(null);

                                for (int i = 0; i < serFields.length; i++) {
                                    ObjectStreamField serField = serFields[i];

                                    FieldInfo fieldInfo;

                                    if (!fieldNames.containsKey(serField.getName())) {
                                        fieldInfo = new FieldInfo(null,
                                            serField.getName(),
                                            -1,
                                            fieldType(serField.getType()));
                                    }
                                    else {
                                        Field f = fieldNames.get(serField.getName());

                                        fieldInfo = new FieldInfo(f,
                                            serField.getName(),
                                            UNSAFE.objectFieldOffset(f),
                                            fieldType(serField.getType()));
                                    }

                                    clsFields.add(fieldInfo);
                                }
                            }
                        }
                        catch (NoSuchFieldException ignored) {
                            // No-op.
                        }
                        catch (IllegalAccessException e) {
                            throw new IOException("Failed to get value of 'serialPersistentFields' field in class: " +
                                cls.getName(), e);
                        }

                        if (!hasSerialPersistentFields) {
                            for (int i = 0; i < clsFields0.length; i++) {
                                Field f = clsFields0[i];

                                int mod = f.getModifiers();

                                if (!isStatic(mod) && !isTransient(mod)) {
                                    FieldInfo fieldInfo = new FieldInfo(f,
                                        f.getName(),
                                        UNSAFE.objectFieldOffset(f),
                                        fieldType(f.getType()));

                                    clsFields.add(fieldInfo);
                                }
                            }
                        }

                        Collections.sort(clsFields, new Comparator<FieldInfo>() {
                            @Override public int compare(FieldInfo t1, FieldInfo t2) {
                                return t1.name().compareTo(t2.name());
                            }
                        });

                        fields.add(new ClassFields(clsFields));
                    }

                    Collections.reverse(writeObjMtds);
                    Collections.reverse(readObjMtds);
                    Collections.reverse(fields);

                    this.fields = new Fields(fields, fieldsIndexingSupported);
                }
            }
        }

        checksum = computeSerialVersionUid(cls, fields != null ? fields.ownFields() : null);
    }

    /**
     * @return Excluded flag.
     */
    boolean excluded() {
        return excluded;
    }

    /**
     * @return Class.
     */
    Class<?> describedClass() {
        return cls;
    }

    /**
     * @return Primitive flag.
     */
    boolean isPrimitive() {
        return isPrimitive;
    }

    /**
     * @return Enum flag.
     */
    boolean isEnum() {
        return isEnum;
    }

    /**
     * @return {@code True} if descriptor is for {@link Class}.
     */
    boolean isClass() {
        return isCls;
    }

    /**
     * Replaces object.
     *
     * @param obj Object.
     * @return Replaced object or {@code null} if there is no {@code writeReplace} method.
     * @throws IOException In case of error.
     */
    Object replace(Object obj) throws IOException {
        if (writeReplaceMtd != null) {
            try {
                return writeReplaceMtd.invoke(obj);
            }
            catch (IllegalAccessException | InvocationTargetException e) {
                throw new IOException(e);
            }
        }

        return obj;
    }

    /**
     * Writes object to stream.
     *
     * @param out Output stream.
     * @param obj Object.
     * @throws IOException In case of error.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    void write(OptimizedObjectOutputStream out, Object obj) throws IOException {
        out.write(type);

        switch (type) {
            case BYTE:
                out.writeByte((Byte)obj);

                break;

            case SHORT:
                out.writeShort((Short)obj);

                break;

            case INT:
                out.writeInt((Integer)obj);

                break;

            case LONG:
                out.writeLong((Long)obj);

                break;

            case FLOAT:
                out.writeFloat((Float)obj);

                break;

            case DOUBLE:
                out.writeDouble((Double)obj);

                break;

            case CHAR:
                out.writeChar((Character)obj);

                break;

            case BOOLEAN:
                out.writeBoolean((Boolean)obj);

                break;

            case BYTE_ARR:
                out.writeByteArray((byte[])obj);

                break;

            case SHORT_ARR:
                out.writeShortArray((short[])obj);

                break;

            case INT_ARR:
                out.writeIntArray((int[])obj);

                break;

            case LONG_ARR:
                out.writeLongArray((long[])obj);

                break;

            case FLOAT_ARR:
                out.writeFloatArray((float[])obj);

                break;

            case DOUBLE_ARR:
                out.writeDoubleArray((double[])obj);

                break;

            case CHAR_ARR:
                out.writeCharArray((char[])obj);

                break;

            case BOOLEAN_ARR:
                out.writeBooleanArray((boolean[])obj);

                break;

            case OBJ_ARR:
                OptimizedClassDescriptor compDesc = classDescriptor(clsMap,
                    obj.getClass().getComponentType(),
                    ctx,
                    mapper,
                    idxHandler);

                compDesc.writeTypeData(out);

                out.writeArray((Object[])obj);

                break;

            case STR:
                out.writeString((String)obj);

                break;

            case UUID:
                out.writeUuid((UUID)obj);

                break;

            case PROPS:
                out.writeProperties((Properties)obj, dfltsFieldOff);

                break;

            case ARRAY_LIST:
                out.writeArrayList((ArrayList<?>)obj);

                break;

            case HASH_MAP:
                out.writeHashMap((HashMap<?, ?>)obj, loadFactorFieldOff, false);

                break;

            case HASH_SET:
                out.writeHashSet((HashSet<?>)obj, HASH_SET_MAP_OFF, loadFactorFieldOff);

                break;

            case LINKED_LIST:
                out.writeLinkedList((LinkedList<?>)obj);

                break;

            case LINKED_HASH_MAP:
                out.writeLinkedHashMap((LinkedHashMap<?, ?>)obj, loadFactorFieldOff, accessOrderFieldOff, false);

                break;

            case LINKED_HASH_SET:
                out.writeLinkedHashSet((LinkedHashSet<?>)obj, HASH_SET_MAP_OFF, loadFactorFieldOff);

                break;

            case DATE:
                out.writeDate((Date)obj);

                break;

            case CLS:
                OptimizedClassDescriptor clsDesc = classDescriptor(clsMap, (Class<?>)obj, ctx, mapper, idxHandler);

                clsDesc.writeTypeData(out);

                break;

            case ENUM:
                writeTypeData(out);

                out.writeInt(((Enum)obj).ordinal());

                break;

            case EXTERNALIZABLE:
                writeTypeData(out);

                out.writeShort(checksum);
                out.writeExternalizable(obj);

                break;

            case MARSHAL_AWARE:
                writeTypeData(out);

                out.writeShort(checksum);
                out.writeMarshalAware(obj);

                if (idxHandler.metaHandler().metadata(typeId) == null) {
                    OptimizedMarshalAwareMetaCollector collector = new OptimizedMarshalAwareMetaCollector();

                    ((OptimizedMarshalAware)obj).writeFields(collector);

                    idxHandler.metaHandler().addMeta(typeId, collector.meta());
                }

                break;

            case SERIALIZABLE:
                if (out.requireSerializable() && !isSerial)
                    throw new NotSerializableException("Must implement java.io.Serializable or " +
                        "set OptimizedMarshaller.setRequireSerializable() to false " +
                        "(note that performance may degrade if object is not Serializable): " + name);

                idxHandler.enableFieldsIndexingForClass(obj.getClass());

                writeTypeData(out);

                out.writeShort(checksum);
                out.writeSerializable(obj, writeObjMtds, fields);

                break;

            default:
                throw new IllegalStateException("Invalid class type: " + type);
        }
    }

    /**
     * @param out Output stream.
     * @throws IOException In case of error.
     */
    void writeTypeData(OptimizedObjectOutputStream out) throws IOException {
        out.writeInt(typeId);

        if (typeId == 0)
            out.writeUTF(name);
    }

    /**
     * Reads object from stream.
     *
     * @param in Input stream.
     * @return Object.
     * @throws ClassNotFoundException If class not found.
     * @throws IOException In case of error.
     */
    Object read(OptimizedObjectInputStream in) throws ClassNotFoundException, IOException {
        switch (type) {
            case ENUM:
                return enumVals[in.readInt()];

            case EXTERNALIZABLE:
                verifyChecksum(in.readShort());

                return in.readExternalizable(constructor, readResolveMtd);

            case SERIALIZABLE:
                verifyChecksum(in.readShort());

                return in.readSerializable(cls, readObjMtds, readResolveMtd, fields);

            case MARSHAL_AWARE:
                verifyChecksum(in.readShort());

                return in.readMarshalAware(constructor, readResolveMtd, typeId);

            default:
                assert false : "Unexpected type: " + type;

                return null;
        }
    }

    /**
     * @param checksum Checksum.
     * @throws ClassNotFoundException If checksum is wrong.
     * @throws IOException In case of error.
     */
    private void verifyChecksum(short checksum) throws ClassNotFoundException, IOException {
        if (checksum != this.checksum)
            throw new ClassNotFoundException("Optimized stream class checksum mismatch " +
                "(is same version of marshalled class present on all nodes?) " +
                "[expected=" + this.checksum + ", actual=" + checksum + ", cls=" + cls + ']');
    }

    /**
     * Returns type ID.
     *
     * @return Type ID.
     */
    public int typeId() {
        if (typeId == 0)
            return resolveTypeId(cls.getName(), mapper);

        return typeId;
    }

    /**
     * Returns class fields.
     *
     * @return Fields.
     */
    public Fields fields() {
        return fields;
    }

    /**
     * @param cls Class.
     * @return Type.
     */
    @SuppressWarnings("IfMayBeConditional")
    private OptimizedFieldType fieldType(Class<?> cls) {
        OptimizedFieldType type;

        if (cls == byte.class)
            type = OptimizedFieldType.BYTE;
        else if (cls == short.class)
            type = OptimizedFieldType.SHORT;
        else if (cls == int.class)
            type = OptimizedFieldType.INT;
        else if (cls == long.class)
            type = OptimizedFieldType.LONG;
        else if (cls == float.class)
            type = OptimizedFieldType.FLOAT;
        else if (cls == double.class)
            type = OptimizedFieldType.DOUBLE;
        else if (cls == char.class)
            type = OptimizedFieldType.CHAR;
        else if (cls == boolean.class)
            type = OptimizedFieldType.BOOLEAN;
        else
            type = OptimizedFieldType.OTHER;

        return type;
    }

    /**
     * Information about one field.
     */
    @SuppressWarnings("PackageVisibleInnerClass")
    public static class FieldInfo {
        /** Field. */
        private final Field field;

        /** Field offset. */
        private final long fieldOffs;

        /** Field type. */
        private final OptimizedFieldType fieldType;

        /** Field name. */
        private final String fieldName;


        /**
         * @param field Field.
         * @param name Field name.
         * @param offset Field offset.
         * @param type Grid optimized field type.
         */
        FieldInfo(Field field, String name, long offset, OptimizedFieldType type) {
            this.field = field;
            fieldOffs = offset;
            fieldType = type;
            fieldName = name;
        }

        /**
         * @return Type.
         */
        public OptimizedFieldType type() {
            return fieldType;
        }

        /**
         * @return Returns field.
         */
        Field field() {
            return field;
        }

        /**
         * @return Offset.
         */
        long offset() {
            return fieldOffs;
        }

        /**
         * @return Name.
         */
        String name() {
            return fieldName;
        }
    }

    /**
     * Information about one class.
     */
    public static class ClassFields {
        /** Fields. */
        private final List<FieldInfo> fields;

        /** */
        private final Map<String, Integer> nameToIndex;

        /**
         * @param fields Field infos.
         */
        ClassFields(List<FieldInfo> fields) {
            this.fields = fields;

            nameToIndex = U.newHashMap(fields.size());

            for (int i = 0; i < fields.size(); ++i)
                nameToIndex.put(fields.get(i).name(), i);
        }

        /**
         * @return Class fields.
         */
        List<FieldInfo> fields() {
            return fields;
        }

        /**
         * @return Fields count.
         */
        int size() {
            return fields.size();
        }

        /**
         * @param i Field's index.
         * @return FieldInfo.
         */
        FieldInfo get(int i) {
            return fields.get(i);
        }

        /**
         * @param name Field's name.
         * @return Field's index.
         */
        int getIndex(String name) {
            assert nameToIndex.containsKey(name);

            return nameToIndex.get(name);
        }

        /**
         * Returns field info list.
         *
         * @return Fields info list.
         */
        public List<FieldInfo> fieldInfoList() {
            return fields;
        }
    }

    /**
     * Encapsulates data about class fields.
     */
    @SuppressWarnings("PackageVisibleInnerClass")
    public static class Fields {
        /** Fields. */
        private final List<ClassFields> fields;

        /** Own fields (excluding inherited). */
        private final List<Field> ownFields;

        /** Fields indexing flag. */
        private final boolean fieldsIndexingSupported;

        /**
         * Creates new instance.
         *
         * @param fields Fields.
         */
        Fields(List<ClassFields> fields, boolean fieldsIndexingSupported) {
            this.fields = fields;
            this.fieldsIndexingSupported = fieldsIndexingSupported;

            if (fields.isEmpty())
                ownFields = null;
            else {
                ownFields = new ArrayList<>(fields.size());

                for (FieldInfo f : fields.get(fields.size() - 1).fields()) {
                    if (f.field() != null)
                        ownFields.add(f.field);
                }
            }
        }

        /**
         * Returns class's own fields (excluding inherited).
         *
         * @return List of fields or {@code null} if fields list is empty.
         */
        List<Field> ownFields() {
            return ownFields;
        }

        /**
         * Returns field types and their offsets.
         *
         * @param i hierarchy level where 0 corresponds to top level.
         * @return list of pairs where first value is field type and second value is its offset.
         */
        ClassFields fields(int i) {
            return fields.get(i);
        }

        /**
         * Whether fields indexing is supported for a given object or not.
         *
         * @return {@code true} if supported, {@code false} otherwise.
         */
        public boolean fieldsIndexingSupported() {
            return fieldsIndexingSupported;
        }

        /**
         * Returns fields list.
         *
         * @return Fields list.
         */
        public List<ClassFields> fieldsList() {
            return fields;
        }
    }
}
