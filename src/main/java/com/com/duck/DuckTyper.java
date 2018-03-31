/*
 * Copyright 2018 BeUndead
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.com.duck;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Utility class for <strong>duck typing</strong> instances to {@code interfaces}.
 *
 * @see #duckType(Object, Class)
 */
public final class DuckTyper {

    /**
     * Private constructor; should not be invoked.
     *
     * @throws AssertionError always
     */
    private DuckTyper() throws AssertionError {
        throw new AssertionError("'DuckTyper' is a utility class; no instances should exist");
    }


    /**
     * <strong>Duck types</strong> the provided {@code instance} to the provided {@code
     * interfaceType}.
     *
     * @param instance      the {@code instance} to be duck typed
     * @param interfaceType the {@code interface} to duck type the provided {@code instance} to
     * @param <T>           the type of the provided {@code interface} (and consequently type of
     *                      the returned duck typed proxy
     *
     * @return a duck typed proxy of the provided {@code instance}, as an implementation of the
     *         provided {@code interfaceType}
     *
     * @throws IllegalArgumentException if duck typing is not supported for the given instance to
     *                                  the given {@code interfaceType}; or if the given {@code
     *                                  interfaceType} is not an {@code interface}.
     * @throws NullPointerException     if either of the given arguments are {@code null}
     */
    public static <T> T duckType(final Object instance, final Class<T> interfaceType)
            throws IllegalArgumentException, NullPointerException {

        // region Argument validation
        if (instance == null) {
            throw new NullPointerException("'instance' must not be 'null'");
        }
        if (interfaceType == null) {
            throw new NullPointerException("'interfaceType' must not be 'null'");
        }
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException("'interfaceType' must be an interface");
        }

        if (interfaceType.isInstance(instance)) {
            // Short circuit if the instance is already an implementation of the provided interface
            return interfaceType.cast(instance);
        }

        if (!DuckTypingInvocationHandler.supports(instance, interfaceType)) {
            throw new IllegalArgumentException("Provided 'instance' cannot be duck typed");
        }
        // endregion Argument validation

        @SuppressWarnings("unchecked")
        final T duckTypedInstance = (T) Proxy.newProxyInstance(DuckTyper.class.getClassLoader(),
                               new Class[] {interfaceType},
                               new DuckTypingInvocationHandler(instance));
        return duckTypedInstance;
    }


    // region Internal
    /**
     * Implementation of {@link InvocationHandler} which supports <strong>duck typing</strong>.
     */
    private static final class DuckTypingInvocationHandler implements InvocationHandler {

        // Need access to the Lookup class' constructor in order to handle default methods in
        // interfaces.  Java provides no way to do this for inaccessible methods without reflection.
        private static final Constructor<Lookup> LOOKUP_CONSTRUCTOR;
        static {
            try {
                LOOKUP_CONSTRUCTOR = Lookup.class.getDeclaredConstructor(Class.class, int.class);
                LOOKUP_CONSTRUCTOR.setAccessible(true);
            } catch (final Throwable th) {
                throw new RuntimeException(th);
            }
        }

        // Cache for finding the duck typed Method.  The keys are the called methods (from the
        // interface) and the values are the associated, duck typed methods from the proxied
        // instance.
        private final ConcurrentMap<Method, Method> methodCache = new ConcurrentHashMap<>();
        // The instance being duck typed.
        private final Object pseudoDuck;

        /**
         * Private constructor; creates a new {@link DuckTypingInvocationHandler} instance.
         *
         * @param pseudoDuck the instance to be <strong>duck typed</strong>.
         */
        private DuckTypingInvocationHandler(final Object pseudoDuck) {
            this.pseudoDuck = pseudoDuck;
        }


        /**
         * {@inheritDoc}
         * <p>
         * Implementation locates the associated method on the {@link #pseudoDuck} to invoke.
         */
        @Override
        public @Nullable Object invoke(final Object proxy, final Method method, final Object[] args)
                throws Throwable {

            final Method methodToInvoke = this.methodCache.computeIfAbsent(
                    method, key -> findMethodInstance(this.pseudoDuck, key));

            assert methodToInvoke != null;

            return this.doInvoke(methodToInvoke, proxy, args);
        }


        /**
         * Performs the actual method invocation.
         *
         * @see #invoke(Object, Method, Object[])
         */
        private @Nullable Object doInvoke(final Method method,
                                          final Object proxy,
                                          final Object[] args) throws Throwable {

            if (method.isDefault()) {
                // Can't proxy a default method, as it'll end up in a StackOverflowException
                // repeatedly falling back to this.  Instead, use unreflectSpecial to invoke.
                // Use the Lookup class' constructor reflectively to allow access to
                // inaccessible methods (non-public interface) this way.
                final Class<?> declaringClass = method.getDeclaringClass();
                return LOOKUP_CONSTRUCTOR
                        .newInstance(declaringClass, Lookup.PRIVATE)
                        .unreflectSpecial(method, declaringClass)
                        .bindTo(proxy)

                        // Don't wrap this in the same try as below.  This approach to invocation
                        // directly propagates any thrown Exceptions, and so manipulating them as
                        // below would mess with methods which actually threw those specific
                        // Exception types.
                        .invokeWithArguments(args);
            } else {
                try {
                    return method.invoke(this.pseudoDuck, args);
                } catch (final InvocationTargetException itEx) {
                    // Rethrow the target Exception.
                    throw itEx.getTargetException();
                } catch (final IllegalAccessException iaEx) {
                    // Caller's own fault for having a SecurityManager in place.  Nothing to do here.
                    throw new IllegalStateException("Failed to invoke proxy", iaEx);
                }
            }
        }


        /**
         * Determines whether <strong>duck typing</strong> is supported for the given {@code
         * instance} to the given {@code interfaceType}.  All sub interfaces are considered.
         *
         * @param instance      the candidate instance for duck typing
         * @param interfaceType the interface class to duck type the provided {@code instance} to
         *
         * @return {@code true} if the provided {@code instance} <strong>can</strong> be duck typed
         *         to the provided {@code interfaceType}; otherwise {@code false}
         */
        private static boolean supports(final Object instance, final Class<?> interfaceType) {

            if (!supportsInterface(instance, interfaceType)) {
                return false;
            }

            for (final Class<?> superInterface : interfaceType.getInterfaces()) {
                if (!supports(instance, superInterface)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Determines whether <strong>duck typing</strong> is supported for the given {@code
         * instance} to the given {@code interfaceType}.  Sub interfaces are not considered.
         *
         * @see #supports(Object, Class)
         */
        private static boolean supportsInterface(final Object instance,
                                                 final Class<?> typeInterface) {
            final Method[] declaredMethods = typeInterface.getDeclaredMethods();

            for (final Method method : declaredMethods) {
                if (findMethodInstance(instance, method) != null) {
                    continue;
                }

                return false;
            }
            return true;
        }


        /**
         * Locates the {@link Method} from the provided {@code instance} to be invoked, in place
         * of the requested {@code method}.
         *
         * @param instance the duck typed instance
         * @param method   the method to be duck typed
         *
         * @return the duck typed {@code Method}; or {@code null} if no such {@code Method} was
         *         found
         */
        private static @Nullable Method findMethodInstance(final Object instance,
                                                           final Method method) {

            @Nullable Method theMethod = null;
            final int methodModifiers = method.getModifiers();
            if (Modifier.isStatic(methodModifiers)) {
                // No need to proxy static methods
                theMethod = method;
            }

            // Check that the Exceptions declared by both methods are compatible
            final Class<?>[] baseExceptionTypes = method.getExceptionTypes();
            if (theMethod == null) {
                Class<?> currentInstanceClass = instance.getClass();
                methodSearchLoop:
                while (currentInstanceClass != null) { // Check all superclasses too
                    tryBlock:
                    try {
                        final Method tempMethod = currentInstanceClass.getDeclaredMethod(
                                method.getName(), method.getParameterTypes());

                        final Class<?>[] tempMethodExceptionTypes = tempMethod.getExceptionTypes();

                        exceptionSearchLoop:
                        for (final Class<?> tempMethodExceptionType : tempMethodExceptionTypes) {
                            if (RuntimeException.class.isAssignableFrom(tempMethodExceptionType)
                                    || Error.class.isAssignableFrom(tempMethodExceptionType)) {
                                continue exceptionSearchLoop;
                            }
                            for (final Class<?> baseExceptionType : baseExceptionTypes) {
                                if (baseExceptionType.isAssignableFrom(tempMethodExceptionType)) {
                                    continue exceptionSearchLoop;
                                }
                            }

                            // Current tempMethodExceptionType is incompatible.
                            // Breaking out leaves theMethod as null.  Which lets default
                            // interface methods be considered.
                            break tryBlock;
                        }
                        theMethod = tempMethod;
                        break methodSearchLoop;
                    } catch (final NoSuchMethodException ignored) {
                        // This is fine, the current class being looked at doesn't contain the
                        // method.  Super classes still may, or it may have a default
                        // implementation in the interface declaration.
                    }

                    // Not found on the currentInstanceClass, so check out super classes.
                    currentInstanceClass = currentInstanceClass.getSuperclass();
                }
            }

            if (theMethod == null && !Modifier.isAbstract(methodModifiers)) {
                // No implementation found in the instance's class tree, but the method has a
                // default implementation from the interface, so use that.
                theMethod = method;
            }

            if (theMethod != null && !theMethod.isAccessible()) {
                theMethod.setAccessible(true);
            }

            return theMethod;
        }
    }
    // endregion Internal
}
