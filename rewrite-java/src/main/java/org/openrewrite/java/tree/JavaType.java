/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.tree;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import lombok.*;
import org.openrewrite.internal.lang.Nullable;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

@JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class, property = "@ref")
@JsonTypeInfo(use = JsonTypeInfo.Id.MINIMAL_CLASS, property = "@c")
public interface JavaType extends Serializable {
    static void clearCaches() {
        Class.flyweights.clear();
        Method.flyweights.clear();
        if (Parameterized.flyweight.children != null) {
            Parameterized.flyweight.children.clear();
        }
        Variable.flyweights.clear();
    }

    boolean deepEquals(@Nullable JavaType type);

    /**
     * Return a JavaType for the specified string.
     * The string is expected to be either a primitive type like "int" or a fully-qualified-class name like "java.lang.String"
     */
    static JavaType buildType(String typeName) {
        Primitive primitive = Primitive.fromKeyword(typeName);
        if (primitive != null) {
            return primitive;
        } else {
            return Class.build(typeName);
        }
    }

    @Data
    @With
    class MultiCatch implements JavaType {
        private final List<JavaType> throwableTypes;

        public MultiCatch(List<JavaType> throwableTypes) {
            this.throwableTypes = throwableTypes;
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            return this == type || (type instanceof MultiCatch &&
                    TypeUtils.deepEquals(throwableTypes, ((MultiCatch) type).throwableTypes));
        }
    }

    abstract class FullyQualified implements JavaType {

        public abstract String getFullyQualifiedName();

        public abstract FullyQualified withFullyQualifiedName(String fullyQualifiedName);

        public abstract List<FullyQualified> getAnnotations();

        public abstract boolean hasFlags(Flag... test);

        public abstract Set<Flag> getFlags();

        public abstract List<FullyQualified> getInterfaces();

        public abstract Class.Kind getKind();

        public abstract List<Variable> getMembers();

        public abstract List<Method> getMethods();

        @Nullable
        public abstract FullyQualified getOwningClass();

        @Nullable
        public abstract FullyQualified getSupertype();

        public abstract List<Variable> getVisibleSupertypeMembers();

        public String getClassName() {
            AtomicBoolean dropWhile = new AtomicBoolean(false);
            return Arrays.stream(getFullyQualifiedName().split("\\."))
                    .filter(part -> {
                        dropWhile.set(dropWhile.get() || !Character.isLowerCase(part.charAt(0)));
                        return dropWhile.get();
                    })
                    .collect(joining("."));
        }

        public String getPackageName() {
            AtomicBoolean takeWhile = new AtomicBoolean(true);
            return Arrays.stream(getFullyQualifiedName().split("\\."))
                    .filter(part -> {
                        takeWhile.set(takeWhile.get() && !Character.isUpperCase(part.charAt(0)));
                        return takeWhile.get();
                    })
                    .collect(joining("."));
        }

        public boolean isAssignableFrom(@Nullable FullyQualified clazz) {
            //TODO This does not take into account type parameters.
            return clazz != null && (this == Class.OBJECT ||
                    getFullyQualifiedName().equals(clazz.getFullyQualifiedName()) ||
                    isAssignableFrom(clazz.getSupertype()) ||
                    clazz.getInterfaces().stream().anyMatch(this::isAssignableFrom));
        }
    }

    /**
     * Reduces memory and CPU footprint when deep class insight isn't necessary, such as
     * for the type parameters of a Type.Class
     */
    @EqualsAndHashCode(callSuper = false)
    @Data
    class ShallowClass extends FullyQualified {
        private final String fullyQualifiedName;

        public ShallowClass(String fullyQualifiedName) {
            this.fullyQualifiedName = fullyQualifiedName;
        }

        @Override
        public Class.Kind getKind() {
            return Class.Kind.Class;
        }

        @Override
        public ShallowClass withFullyQualifiedName(String fullyQualifiedName) {
            if (this.fullyQualifiedName.equals(fullyQualifiedName)) {
                return this;
            }
            return new ShallowClass(fullyQualifiedName);
        }

        @Override
        public List<FullyQualified> getAnnotations() {
            return emptyList();
        }

        @Override
        public boolean hasFlags(Flag... test) {
            return test.length == 1 && test[0] == Flag.Public;
        }

        @Override
        public Set<Flag> getFlags() {
            return Collections.singleton(Flag.Public);
        }

        @Override
        public List<FullyQualified> getInterfaces() {
            return emptyList();
        }

        @Override
        public List<Variable> getMembers() {
            return emptyList();
        }

        @Override
        public List<Method> getMethods() {
            return emptyList();
        }

        @Override
        public FullyQualified getOwningClass() {
            return null;
        }

        @Override
        public FullyQualified getSupertype() {
            return Class.OBJECT;
        }

        @Override
        public List<Variable> getVisibleSupertypeMembers() {
            return emptyList();
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            return this == type || (type instanceof ShallowClass &&
                    fullyQualifiedName.equals(((ShallowClass) type).fullyQualifiedName));
        }

        @Override
        public String toString() {
            return "ShallowClass(" + fullyQualifiedName + ")";
        }
    }

    @Getter
    class Class extends FullyQualified {
        // there shouldn't be too many distinct types represented by the same fully qualified name
        private static final Map<String, Set<Class>> flyweights = new WeakHashMap<>();

        public static final Class OBJECT = build("java.lang.Object");

        private final String fullyQualifiedName;

        @Getter(AccessLevel.NONE)
        private final long flagsBitMap;
        private final Kind kind;
        private final List<FullyQualified> annotations;
        private final List<Variable> members;
        private final List<FullyQualified> interfaces;
        private final List<Method> methods;

        @Nullable
        private final FullyQualified supertype;

        @Nullable
        private final FullyQualified owningClass;

        private Class(long flagsBitMap,
                      String fullyQualifiedName,
                      Kind kind,
                      List<Variable> members,
                      List<FullyQualified> interfaces,
                      List<Method> methods,
                      @Nullable FullyQualified supertype,
                      @Nullable FullyQualified owningClass,
                      List<FullyQualified> annotations) {
            this.fullyQualifiedName = fullyQualifiedName;
            this.flagsBitMap = flagsBitMap;
            this.kind = kind;
            this.members = members;
            this.interfaces = interfaces;
            this.methods = methods;
            this.supertype = supertype;
            this.owningClass = owningClass;
            this.annotations = annotations;
        }

        @Override
        public JavaType.Class withFullyQualifiedName(String fullyQualifiedName) {
            if (this.fullyQualifiedName.equals(fullyQualifiedName)) {
                return this;
            }
            return JavaType.Class.build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations);
        }

        public JavaType.Class withAnnotations(List<FullyQualified> annotations) {
            if (this.annotations == annotations) {
                return this;
            }
            return JavaType.Class.build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations);
        }

        public JavaType.Class withInterfaces(List<FullyQualified> interfaces) {
            if (this.interfaces == interfaces) {
                return this;
            }
            return JavaType.Class.build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations);
        }

        public JavaType.Class withMembers(List<Variable> members) {
            if (this.members == members) {
                return this;
            }
            return JavaType.Class.build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations);
        }

        public JavaType.Class withMethods(List<Method> methods) {
            if (this.methods == methods) {
                return this;
            }
            return JavaType.Class.build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations);
        }

        public JavaType.Class withSupertype(@Nullable FullyQualified supertype) {
            if (this.supertype == supertype) {
                return this;
            }
            return JavaType.Class.build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations);
        }

        public JavaType.Class withOwningClass(@Nullable FullyQualified owningClass) {
            if (this.owningClass == owningClass) {
                return this;
            }
            return JavaType.Class.build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations);
        }

        public boolean hasFlags(Flag... test) {
            return Flag.hasFlags(flagsBitMap, test);
        }

        public Set<Flag> getFlags() {
            return Flag.bitMapToFlags(flagsBitMap);
        }

        /**
         * Find an existing type definition if it already exists. This does not create
         * a shallow type and place it in the flyweight map if it does not already exist.
         *
         * @param fullyQualifiedName The fully qualified name to find.
         * @return The first existing type definition matching this fully qualified name, if any.
         */
        @Nullable
        public static Class find(String fullyQualifiedName) {
            Set<JavaType.Class> variants = flyweights.get(fullyQualifiedName);
            return (variants == null || variants.isEmpty()) ? null : variants.iterator().next();
        }

        /**
         * Build a class type only from the class' fully qualified name. Since we are not providing any member, type parameter,
         * interface, or supertype information, this fully qualified name could potentially match on more than one version of
         * the class found in the type cache. This method will simply pick one of them, because there is no way of selecting
         * between the versions of the class based solely on the fully qualified class name.
         *
         * @param fullyQualifiedName The fully qualified name of the class to build
         * @return Any class found in the type cache
         */
        public static Class build(String fullyQualifiedName) {
            Class owningClass = null;

            int firstClassNameIndex = 0;
            int lastDot = 0;
            char[] fullyQualifiedNameChars = fullyQualifiedName.toCharArray();
            char prev = ' ';
            for (int i = 0; i < fullyQualifiedNameChars.length; i++) {
                char c = fullyQualifiedNameChars[i];

                if (firstClassNameIndex == 0 && prev == '.' && Character.isUpperCase(c)) {
                    firstClassNameIndex = i;
                } else if (c == '.') {
                    lastDot = i;
                }
                prev = c;
            }

            if (lastDot > firstClassNameIndex) {
                owningClass = build(fullyQualifiedName.substring(0, lastDot));
            }

            return build(1, fullyQualifiedName, Kind.Class, emptyList(),
                    emptyList(), emptyList(), null,
                    owningClass, emptyList(), true);
        }

        /**
         * Build a class type only from the class' fully qualified name and kind. Since we are not providing any member,
         * type parameter, interface, or supertype information, this fully qualified name could potentially match on
         * more than one version of the class found in the type cache. This method will simply pick one of them, because
         * there is no way of selecting between the versions of the class based solely on the fully qualified class name.
         *
         * @param fullyQualifiedName The fully qualified name of the class to build
         * @param kind               The class kind : Class, Annotation, Enum, or Interface
         * @return Any class found in the type cache
         */
        public static Class build(String fullyQualifiedName, Kind kind) {
            return build(1, fullyQualifiedName, kind, emptyList(), emptyList(), emptyList(), null, null, emptyList(), true);
        }

        public static Class build(Set<Flag> flags,
                                  String fullyQualifiedName,
                                  Kind kind,
                                  List<Variable> members,
                                  List<FullyQualified> interfaces,
                                  List<Method> methods,
                                  @Nullable FullyQualified supertype,
                                  @Nullable FullyQualified owningClass) {
            return build(Flag.flagsToBitMap(flags), fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, emptyList(), false);
        }

        public static Class build(Set<Flag> flags,
                                  String fullyQualifiedName,
                                  Kind kind,
                                  List<Variable> members,
                                  List<FullyQualified> interfaces,
                                  List<Method> methods,
                                  @Nullable FullyQualified supertype,
                                  @Nullable FullyQualified owningClass,
                                  List<FullyQualified> annotations) {
            return build(Flag.flagsToBitMap(flags), fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations, false);
        }

        @JsonCreator
        protected static Class build(long flagsBitMap,
                                     String fullyQualifiedName,
                                     Kind kind,
                                     List<Variable> members,
                                     List<FullyQualified> interfaces,
                                     List<Method> methods,
                                     @Nullable FullyQualified supertype,
                                     @Nullable FullyQualified owningClass,
                                     List<FullyQualified> annotations) {
            return build(flagsBitMap, fullyQualifiedName, kind, members, interfaces, methods, supertype, owningClass, annotations, false);
        }

        public static Class build(long flagsBitMap,
                                  String fullyQualifiedName,
                                  Kind kind,
                                  List<Variable> members,
                                  List<FullyQualified> interfaces,
                                  List<Method> methods,
                                  @Nullable FullyQualified supertype,
                                  @Nullable FullyQualified owningClass,
                                  List<FullyQualified> annotations,
                                  boolean relaxedClassTypeMatching) {
            Set<JavaType.Class> variants = flyweights.get(fullyQualifiedName);
            if (relaxedClassTypeMatching && variants != null && !variants.isEmpty()) {
                // no lock access to existing flyweight when relaxed class type matching is off
                return variants.iterator().next();
            }

            synchronized (flyweights) {
                variants = flyweights.computeIfAbsent(fullyQualifiedName, fqn -> new HashSet<>());

                if (relaxedClassTypeMatching) {
                    if (variants.isEmpty()) {
                        JavaType.Class candidate = buildCandidate(flagsBitMap, fullyQualifiedName,
                                kind, members, interfaces, methods, supertype, owningClass, annotations);
                        variants.add(candidate);
                        return candidate;
                    }
                    return variants.iterator().next();
                } else {
                    JavaType.Class candidate = buildCandidate(flagsBitMap, fullyQualifiedName,
                            kind, members, interfaces, methods, supertype, owningClass, annotations);

                    for (Class v : variants) {
                        if (v.deepEquals(candidate)) {
                            return v;
                        }
                    }

                    if (candidate.supertype == null) {
                        for (Class variant : variants) {
                            if (variant.supertype != null) {
                                return variant;
                            } else {
                                variants.add(candidate);
                                return candidate;
                            }
                        }
                    }
                    variants.add(candidate);
                    return candidate;
                }
            }
        }

        private static JavaType.Class buildCandidate(long flagsBitMap,
                                                     String fullyQualifiedName,
                                                     Kind kind,
                                                     List<Variable> members,
                                                     List<FullyQualified> interfaces,
                                                     List<Method> methods,
                                                     @Nullable FullyQualified supertype,
                                                     @Nullable FullyQualified owningClass,
                                                     List<FullyQualified> annotations) {
            List<Variable> sortedMembers;
            if (!members.isEmpty()) {
                if (fullyQualifiedName.equals("java.lang.String")) {
                    // there is a "serialPersistentFields" member within the String class which is used in normal Java
                    // serialization to customize how the String field is serialized. This field is tripping up Jackson
                    // serialization and is intentionally filtered to prevent errors.
                    sortedMembers = new ArrayList<>(members.size() - 1);
                    for (Variable m : members) {
                        if (!m.getName().equals("serialPersistentFields")) {
                            sortedMembers.add(m);
                        }
                    }
                } else {
                    sortedMembers = new ArrayList<>(members);
                }
                sortedMembers.sort(comparing(Variable::getName));
            } else {
                sortedMembers = members;
            }

            return new Class(flagsBitMap, fullyQualifiedName, kind, sortedMembers, interfaces, methods, supertype, owningClass, annotations);
        }

        public List<Variable> getVisibleSupertypeMembers() {
            List<Variable> members = new ArrayList<>();
            if (this.supertype != null) {
                for (Variable member : this.supertype.getMembers()) {
                    if (!member.hasFlags(Flag.Private)) {
                        members.add(member);
                    }
                }
                members.addAll(supertype.getVisibleSupertypeMembers());
            }
            return members;
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            if (!(type instanceof Class)) {
                return false;
            }

            Class c = (Class) type;
            return this == c || (kind == c.kind && flagsBitMap == c.flagsBitMap &&
                    fullyQualifiedName.equals(c.fullyQualifiedName) &&
                    TypeUtils.deepEquals(members, c.members) &&
                    TypeUtils.deepEquals(interfaces, c.interfaces) &&
                    TypeUtils.deepEquals(supertype, c.supertype)) &&
                    TypeUtils.deepEquals(annotations, c.annotations);
        }

        @Override
        public String toString() {
            return "Class{" + fullyQualifiedName + '}';
        }

        public enum Kind {
            Class,
            Enum,
            Interface,
            Annotation
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = false)
    class Parameterized extends FullyQualified {
        private static class TypeTrieNode {
            private Map<JavaType, TypeTrieNode> children;
            private Parameterized parameterized;

            public Parameterized find(FullyQualified type, List<JavaType> typeParameters) {
                TypeTrieNode node = find(type, typeParameters, -1);
                return node.parameterized;
            }

            private TypeTrieNode find(FullyQualified type, List<JavaType> typeParameters, int index) {
                if (children == null) {
                    children = new IdentityHashMap<>(4);
                }

                TypeTrieNode node;
                if (index == -1) {
                    node = children.computeIfAbsent(type, t -> new TypeTrieNode());
                } else {
                    node = children.computeIfAbsent(typeParameters.get(index), t -> new TypeTrieNode());
                }

                if (index == typeParameters.size() - 1) {
                    if (node.parameterized == null) {
                        node.parameterized = new Parameterized(type, typeParameters);
                    }
                    return node;
                } else {
                    return node.find(type, typeParameters, index + 1);
                }
            }
        }

        private static final TypeTrieNode flyweight = new TypeTrieNode();

        private final FullyQualified type;
        private final List<JavaType> typeParameters;

        @JsonCreator
        public static Parameterized build(FullyQualified type, List<JavaType> typeParameters) {
            return flyweight.find(type, typeParameters);
        }

        private Parameterized(FullyQualified type, List<JavaType> typeParameters) {
            this.type = type;
            this.typeParameters = typeParameters;
        }

        @Override
        public String getFullyQualifiedName() {
            return type.getFullyQualifiedName();
        }

        @Override
        public Parameterized withFullyQualifiedName(String fullyQualifiedName) {
            if (type.getFullyQualifiedName().equals(fullyQualifiedName)) {
                return this;
            }

            return build(type.withFullyQualifiedName(fullyQualifiedName), typeParameters);
        }

        public JavaType.Parameterized withType(FullyQualified type) {
            if (this.type == type) {
                return this;
            }
            return JavaType.Parameterized.build(type, typeParameters);
        }

        public JavaType.Parameterized withTypeParameters(List<JavaType> typeParameters) {
            if (this.typeParameters == typeParameters) {
                return this;
            }
            return JavaType.Parameterized.build(type, typeParameters);
        }

        @Override
        public List<FullyQualified> getAnnotations() {
            return type.getAnnotations();
        }

        @Override
        public boolean hasFlags(Flag... test) {
            return type.hasFlags();
        }

        @Override
        public Set<Flag> getFlags() {
            return type.getFlags();
        }

        @Override
        public List<FullyQualified> getInterfaces() {
            return type.getInterfaces();
        }

        @Override
        public JavaType.Class.Kind getKind() {
            return type.getKind();
        }

        @Override
        public List<Variable> getMembers() {
            return type.getMembers();
        }

        @Override
        public List<Method> getMethods() {
            return type.getMethods();
        }

        @Override
        public FullyQualified getOwningClass() {
            return type.getOwningClass();
        }

        @Override
        public FullyQualified getSupertype() {
            return type.getSupertype();
        }

        @Override
        public List<Variable> getVisibleSupertypeMembers() {
            return type.getVisibleSupertypeMembers();
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            if (!(type instanceof Parameterized)) {
                return false;
            }

            Parameterized p = (Parameterized) type;
            return this == p || (TypeUtils.deepEquals(this.type, p.type) &&
                    TypeUtils.deepEquals(this.typeParameters, p.typeParameters));
        }
    }

    @EqualsAndHashCode(callSuper = false)
    @Data
    class Cyclic extends FullyQualified {
        private final String fullyQualifiedName;

        public Cyclic(String fullyQualifiedName) {
            this.fullyQualifiedName = fullyQualifiedName;
        }

        @Override
        public Class.Kind getKind() {
            return Class.Kind.Class;
        }

        @Override
        public Cyclic withFullyQualifiedName(String fullyQualifiedName) {
            if (this.fullyQualifiedName.equals(fullyQualifiedName)) {
                return this;
            }
            return new Cyclic(fullyQualifiedName);
        }

        @Override
        public List<FullyQualified> getAnnotations() {
            return emptyList();
        }

        @Override
        public boolean hasFlags(Flag... test) {
            return test.length == 1 && test[0] == Flag.Public;
        }

        @Override
        public Set<Flag> getFlags() {
            return Collections.singleton(Flag.Public);
        }

        @Override
        public List<FullyQualified> getInterfaces() {
            return emptyList();
        }

        @Override
        public List<Variable> getMembers() {
            return emptyList();
        }

        @Override
        public List<Method> getMethods() {
            return emptyList();
        }

        @Override
        public Class getOwningClass() {
            return null;
        }

        @Override
        public Class getSupertype() {
            return Class.OBJECT;
        }

        @Override
        public List<Variable> getVisibleSupertypeMembers() {
            return emptyList();
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            return this.equals(type);
        }

        @Override
        public String toString() {
            return "Cyclic{" + fullyQualifiedName + '}';
        }
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    class Variable implements JavaType {
        private static final Map<String, Map<JavaType, Set<Variable>>> flyweights = new WeakHashMap<>();

        private final String name;

        private final JavaType.FullyQualified owner;

        @Nullable
        private final JavaType type;

        private final List<FullyQualified> annotations;

        @Getter(AccessLevel.NONE)
        private final long flagsBitMap;

        private Variable(String name, JavaType.FullyQualified owner, @Nullable JavaType type,
                         List<FullyQualified> annotations, long flagsBitMap) {
            this.name = name;
            this.type = type;
            this.owner = owner;
            this.annotations = annotations;
            this.flagsBitMap = flagsBitMap;
        }

        @JsonCreator
        public static Variable build(String name, JavaType.FullyQualified owner, @Nullable JavaType type,
                                     List<FullyQualified> annotations, long flagsBitMap) {
            Variable test = new Variable(name, owner, type, annotations, flagsBitMap);

            synchronized (flyweights) {
                Set<Variable> variables = flyweights
                        .computeIfAbsent(name, n -> new WeakHashMap<>())
                        .computeIfAbsent(type, fq -> Collections.newSetFromMap(new WeakHashMap<>()));

                for (Variable variable : variables) {
                    if (variable.deepEquals(test)) {
                        return variable;
                    }
                }

                variables.add(test);
                return test;
            }
        }

        public boolean hasFlags(Flag... test) {
            return Flag.hasFlags(flagsBitMap, test);
        }

        public Set<Flag> getFlags() {
            return Flag.bitMapToFlags(flagsBitMap);
        }

        public JavaType.Variable withOwner(FullyQualified owner) {
            if (this.owner == owner) {
                return this;
            }
            return JavaType.Variable.build(name, owner, type, annotations, flagsBitMap);
        }

        public JavaType.Variable withType(@Nullable JavaType type) {
            if (this.type == type) {
                return this;
            }
            return JavaType.Variable.build(name, owner, type, annotations, flagsBitMap);
        }

        public JavaType.Variable withAnnotations(List<FullyQualified> annotations) {
            if (this.annotations == annotations) {
                return this;
            }
            return JavaType.Variable.build(name, owner, type, annotations, flagsBitMap);
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            if (!(type instanceof Variable)) {
                return false;
            }

            Variable v = (Variable) type;
            return this == v || (name.equals(v.name) &&
                    flagsBitMap == v.flagsBitMap &&
                    TypeUtils.deepEquals(this.owner, v.owner) &&
                    TypeUtils.deepEquals(this.type, v.type) &&
                    TypeUtils.deepEquals(annotations, v.annotations));
        }
    }

    @Getter
    class Method implements JavaType {
        private static final Map<FullyQualified, Map<String, Set<Method>>> flyweights = new WeakHashMap<>();

        @Getter(AccessLevel.NONE)
        private final long flagsBitMap;

        private final FullyQualified declaringType;

        private final String name;

        @Nullable
        private final Signature genericSignature;

        @Nullable
        private final Signature resolvedSignature;

        private final List<String> paramNames;
        private final List<FullyQualified> thrownExceptions;
        private final List<FullyQualified> annotations;

        private Method(long flagsBitMap, FullyQualified declaringType, String name,
                       @Nullable Signature genericSignature, @Nullable Signature resolvedSignature, List<String> paramNames,
                       List<FullyQualified> thrownExceptions, List<FullyQualified> annotations) {
            this.flagsBitMap = flagsBitMap;
            this.declaringType = declaringType;
            this.name = name;
            this.genericSignature = genericSignature;
            this.resolvedSignature = resolvedSignature;
            this.paramNames = paramNames;
            this.thrownExceptions = thrownExceptions;
            this.annotations = annotations;
        }

        public static Method build(Set<Flag> flags, FullyQualified declaringType, String name,
                                   @Nullable Signature genericSignature, @Nullable Signature resolvedSignature,
                                   List<String> paramNames, List<FullyQualified> thrownExceptions,
                                   List<FullyQualified> annotations) {
            return build(Flag.flagsToBitMap(flags), declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        @JsonCreator
        public static Method build(long flagsBitMap, FullyQualified declaringType, String name,
                                   @Nullable Signature genericSignature, @Nullable Signature resolvedSignature, List<String> paramNames,
                                   List<FullyQualified> thrownExceptions, List<FullyQualified> annotations) {
            Method test = new Method(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);

            synchronized (flyweights) {
                Set<Method> methods = flyweights
                        .computeIfAbsent(declaringType, dt -> new WeakHashMap<>())
                        .computeIfAbsent(name, n -> Collections.newSetFromMap(new WeakHashMap<>()));

                for (Method method : methods) {
                    if (method.deepEquals(test)) {
                        return method;
                    }
                }

                methods.add(test);
                return test;
            }
        }

        @Data
        public static class Signature implements Serializable {
            @Nullable
            @With
            private final JavaType returnType;

            @With
            private final List<JavaType> paramTypes;
        }

        private static boolean signatureDeepEquals(@Nullable Signature s1, @Nullable Signature s2) {
            return s1 == null ? s2 == null : s1 == s2 || (s2 != null &&
                    TypeUtils.deepEquals(s1.returnType, s2.returnType) &&
                    TypeUtils.deepEquals(s1.paramTypes, s2.paramTypes));
        }

        public boolean hasFlags(Flag... test) {
            return Flag.hasFlags(flagsBitMap, test);
        }

        public Set<Flag> getFlags() {
            return Flag.bitMapToFlags(flagsBitMap);
        }

        public Method withName(String name) {
            if (this.name.equals(name)) {
                return this;
            }
            return Method.build(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        public Method withFlags(Set<Flag> flags) {
            long flagsBitMap = Flag.flagsToBitMap(flags);
            if (this.flagsBitMap == flagsBitMap) {
                return this;
            }
            return Method.build(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        public Method withAnnotations(List<FullyQualified> annotations) {
            if (this.annotations == annotations) {
                return this;
            }
            return Method.build(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        public Method withDeclaringType(FullyQualified declaringType) {
            if (this.declaringType.equals(declaringType)) {
                return this;
            }
            return Method.build(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        public Method withGenericSignature(@Nullable Signature genericSignature) {
            if ((genericSignature == null && this.genericSignature == null) ||
                    (genericSignature != null && genericSignature.equals(this.genericSignature))) {
                return this;
            }
            return Method.build(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        public Method withResolvedSignature(@Nullable Signature resolvedSignature) {
            if ((resolvedSignature == null && this.resolvedSignature == null) ||
                    (resolvedSignature != null && resolvedSignature.equals(this.resolvedSignature))) {
                return this;
            }
            return Method.build(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        public Method withThrownExceptions(List<FullyQualified> thrownExceptions) {
            if (this.thrownExceptions == thrownExceptions) {
                return this;
            }
            return Method.build(flagsBitMap, declaringType, name, genericSignature, resolvedSignature, paramNames, thrownExceptions, annotations);
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            if (!(type instanceof Method)) {
                return false;
            }

            Method m = (Method) type;
            return this == m || (paramNames.equals(m.paramNames) &&
                    flagsBitMap == m.flagsBitMap &&
                    declaringType.deepEquals(m.declaringType) &&
                    signatureDeepEquals(genericSignature, m.genericSignature) &&
                    signatureDeepEquals(resolvedSignature, m.resolvedSignature) &&
                    TypeUtils.deepEquals(thrownExceptions, m.thrownExceptions) &&
                    TypeUtils.deepEquals(annotations, m.annotations));
        }

        @Override
        public String toString() {
            return "Method{" +
                    declaringType.getFullyQualifiedName() + " " +
                    name + "(" +
                    String.join(", ", paramNames) + ")}";
        }
    }

    @EqualsAndHashCode(callSuper = false)
    @Data
    class GenericTypeVariable extends FullyQualified {
        private final String fullyQualifiedName;

        @Nullable
        @With
        private final FullyQualified bound;

        @Override
        public GenericTypeVariable withFullyQualifiedName(String fullyQualifiedName) {
            if (this.fullyQualifiedName.equals(fullyQualifiedName)) {
                return this;
            }
            return new GenericTypeVariable(fullyQualifiedName, bound);
        }

        @Override
        public List<FullyQualified> getAnnotations() {
            return bound == null ? emptyList() : bound.getAnnotations();
        }

        @Override
        public Class.Kind getKind() {
            return Class.Kind.Class;
        }

        @Override
        public boolean hasFlags(Flag... test) {
            return bound != null && bound.hasFlags(test);
        }

        @Override
        public Set<Flag> getFlags() {
            return bound == null ? emptySet() : bound.getFlags();
        }

        @Override
        public List<FullyQualified> getInterfaces() {
            return bound == null ? emptyList() : bound.getInterfaces();
        }

        @Override
        public List<Variable> getMembers() {
            return bound == null ? emptyList() : bound.getMembers();
        }

        @Override
        public List<Method> getMethods() {
            return bound == null ? emptyList() : bound.getMethods();
        }

        @Override
        public FullyQualified getOwningClass() {
            return bound == null ? null : bound.getOwningClass();
        }

        @Override
        public FullyQualified getSupertype() {
            return bound == null ? null : bound.getSupertype();
        }

        @Override
        public List<Variable> getVisibleSupertypeMembers() {
            return bound == null ? emptyList() : bound.getVisibleSupertypeMembers();
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            if (!(type instanceof GenericTypeVariable)) {
                return false;
            }

            GenericTypeVariable generic = (GenericTypeVariable) type;
            return this == generic || (fullyQualifiedName.equals(generic.fullyQualifiedName) &&
                    TypeUtils.deepEquals(bound, generic.bound));
        }
    }

    @Data
    class Array implements JavaType {
        @Nullable
        private final JavaType elemType;

        public Array(@Nullable JavaType elemType) {
            this.elemType = elemType;
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            return type instanceof Array && (this == type || (elemType != null && elemType.deepEquals(((Array) type).elemType)));
        }
    }

    enum Primitive implements JavaType {
        Boolean,
        Byte,
        Char,
        Double,
        Float,
        Int,
        Long,
        Short,
        Void,
        String,
        None,
        Wildcard,
        Null;

        @Nullable
        public static Primitive fromKeyword(String keyword) {
            switch (keyword) {
                case "boolean":
                    return Boolean;
                case "byte":
                    return Byte;
                case "char":
                    return Char;
                case "double":
                    return Double;
                case "float":
                    return Float;
                case "int":
                    return Int;
                case "long":
                    return Long;
                case "short":
                    return Short;
                case "void":
                    return Void;
                case "String":
                    return String;
                case "*":
                    return Wildcard;
                case "null":
                    return Null;
                case "":
                    return None;
            }
            return null;
        }

        public String getKeyword() {
            switch (this) {
                case Boolean:
                    return "boolean";
                case Byte:
                    return "byte";
                case Char:
                    return "char";
                case Double:
                    return "double";
                case Float:
                    return "float";
                case Int:
                    return "int";
                case Long:
                    return "long";
                case Short:
                    return "short";
                case Void:
                    return "void";
                case String:
                    return "String";
                case Wildcard:
                    return "*";
                case Null:
                    return "null";
                case None:
                default:
                    return "";
            }
        }

        @Override
        public boolean deepEquals(@Nullable JavaType type) {
            return this == type;
        }
    }
}
