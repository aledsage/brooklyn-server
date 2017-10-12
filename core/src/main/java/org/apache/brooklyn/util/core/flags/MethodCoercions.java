/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.util.core.flags;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Reflections;
import org.apache.brooklyn.util.javalang.coerce.ClassCoercionException;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;

/**
 * A way of binding a loosely-specified method call into a strongly-typed Java method call.
 */
public class MethodCoercions {

    /**
     * Returns a predicate that matches a method with the given name, and a single parameter that
     * {@link org.apache.brooklyn.util.core.flags.TypeCoercions#tryCoerce(Object, com.google.common.reflect.TypeToken)} can process
     * from the given argument.
     *
     * @param methodName name of the method
     * @param argument argument that is intended to be given
     * @return a predicate that will match a compatible method
     */
    public static Predicate<Method> matchSingleParameterMethod(final String methodName, final Object argument) {
        checkNotNull(methodName, "methodName");
        checkNotNull(argument, "argument");

        return new Predicate<Method>() {
            @Override
            public boolean apply(@Nullable Method input) {
                if (input == null) return false;
                if (!input.getName().equals(methodName)) return false;
                return tryMatchSingleParameterMethod(input, argument).isPresent();
            }
        };
    }

    /**
     * Tries to find a single-parameter method with a parameter compatible with (can be coerced to) the argument, and
     * invokes it.
     *
     * @param instance the object to invoke the method on
     * @param methodName the name of the method to invoke
     * @param argument the argument to the method's parameter.
     * @return the result of the method call, or {@link org.apache.brooklyn.util.guava.Maybe#absent()} if method could not be matched.
     */
    public static Maybe<?> tryFindAndInvokeSingleParameterMethod(final Object instance, final String methodName, final Object argument) {
        Class<?> clazz = instance.getClass();
        Iterable<Method> methods = Iterables.filter(Arrays.asList(clazz.getMethods()), matchMethodByName(methodName));
        Method method;
        if (Iterables.isEmpty(methods)) {
            return Maybe.absent("No method found named '"+methodName+"' on "+clazz.getName());
        } else if (Iterables.size(methods) == 1) {
            Maybe<Method> matchingMethod = tryMatchSingleParameterMethod(Iterables.getOnlyElement(methods), argument);
            if (matchingMethod.isAbsent()) {
                return matchingMethod;
            } else {
                method = matchingMethod.get();
            }
        } else {
            Optional<Method> matchingMethod = Iterables.tryFind(methods, matchSingleParameterMethod(methodName, argument));
            if (matchingMethod.isPresent()) {
                method = matchingMethod.get();
            } else {
                return Maybe.absent("No method '"+methodName+"' matching supplied arguments on "+instance);
            }
        }

        Method accessibleMethod = Reflections.findAccessibleMethod(method).get();
        try {
            Type paramType = method.getGenericParameterTypes()[0];
            Object coercedArgument = TypeCoercions.coerce(argument, TypeToken.of(paramType));
            return Maybe.of(accessibleMethod.invoke(instance, coercedArgument));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw Exceptions.propagate(e);
        }
    }
    
    /**
     * Returns a predicate that matches a method with the given name, and parameters that
     * {@link org.apache.brooklyn.util.core.flags.TypeCoercions#tryCoerce(Object, com.google.common.reflect.TypeToken)} can process
     * from the given list of arguments.
     *
     * @param methodName name of the method
     * @param arguments arguments that is intended to be given
     * @return a predicate that will match a compatible method
     */
    public static Predicate<Method> matchMultiParameterMethod(final String methodName, final List<?> arguments) {
        return Predicates.and(matchMethodByName(methodName), matchMultiParameterMethod(arguments));
    }

    /**
     * Returns a predicate that matches a method with the given name.
     *
     * @param methodName name of the method
     * @return a predicate that will match a compatible method
     */
    public static Predicate<Method> matchMethodByName(final String methodName) {
        checkNotNull(methodName, "methodName");

        return new Predicate<Method>() {
            @Override
            public boolean apply(@Nullable Method input) {
                return (input != null) && input.getName().equals(methodName);
            }
        };
    }

    /**
     * Returns a predicate that matches a method with parameters that
     * {@link org.apache.brooklyn.util.core.flags.TypeCoercions#tryCoerce(Object, com.google.common.reflect.TypeToken)} can process
     * from the given list of arguments.
     *
     * @param arguments arguments that is intended to be given
     * @return a predicate that will match a compatible method
     */
    public static Predicate<Method> matchMultiParameterMethod(final List<?> arguments) {
        checkNotNull(arguments, "arguments");

        return new Predicate<Method>() {
            @Override
            public boolean apply(@Nullable Method input) {
                if (input == null) return false;
                return tryMatchMultiParameterMethod(input, arguments).isPresent();
            }
        };
    }

    private static Maybe<Method> tryMatchMultiParameterMethod(Method method, final List<?> arguments) {
        checkNotNull(method, "method");
        checkNotNull(arguments, "arguments");

        int numOptionParams = arguments.size();
        Type[] parameterTypes = method.getGenericParameterTypes();
        if (parameterTypes.length != numOptionParams) {
            return Maybe.absent("Incorrect number of arguments to '"+method.getName()+"' (given "+numOptionParams+", expected "+parameterTypes.length+")");
        }

        for (int paramCount = 0; paramCount < numOptionParams; paramCount++) {
            Object arg = ((List<?>) arguments).get(paramCount);
            if (tryCoerce(arg, parameterTypes[paramCount]).isAbsent()) {
                return Maybe.absent("Parameter "+paramCount+" does not match type "+parameterTypes[paramCount]);
            }
        }
        return Maybe.of(method);
    }

    private static Maybe<Method> tryMatchSingleParameterMethod(Method method, final Object argument) {
        checkNotNull(method, "method");
        checkNotNull(argument, "argument");

        Type[] parameterTypes = method.getGenericParameterTypes();
        if (parameterTypes.length != 1) {
            return Maybe.absent("Incorrect number of arguments to '"+method.getName()+"' (expected "+parameterTypes.length+")");
        } else if (tryCoerce(argument, parameterTypes[0]).isAbsentOrNull()) {
            return Maybe.absent("Parameter does not match type "+parameterTypes[0]);
        } else {
            return Maybe.of(method);
        }
    }

    /**
     * Tries to find a multiple-parameter method with each parameter compatible with (can be coerced to) the
     * corresponding argument, and invokes it.
     *
     * @param instanceOrClazz the object or class to invoke the method on
     * @param methods the methods to choose from
     * @param argument a list of the arguments to the method's parameters.
     * @return the result of the method call, or {@link org.apache.brooklyn.util.guava.Maybe#absent()} if method could not be matched.
     */
    public static Maybe<?> tryFindAndInvokeMultiParameterMethod(Object instanceOrClazz, Iterable<Method> methods, List<?> arguments) {
        Method method;
        if (Iterables.isEmpty(methods)) {
            return Maybe.absent("No supplied methods to choose from on "+instanceOrClazz);
        } else if (Iterables.size(methods) == 1) {
            Maybe<Method> matchingMethod = tryMatchMultiParameterMethod(Iterables.getOnlyElement(methods), arguments);
            if (matchingMethod.isAbsent()) {
                return matchingMethod;
            } else {
                method = matchingMethod.get();
            }
        } else {
            Optional<Method> matchingMethod = Iterables.tryFind(methods, matchMultiParameterMethod(arguments));
            if (matchingMethod.isPresent()) {
                method = matchingMethod.get();
            } else {
                return Maybe.absent("No method matching supplied arguments on "+instanceOrClazz);
            }
        }
        
        Method accessibleMethod = Reflections.findAccessibleMethod(method).get();
        try {
            int numOptionParams = ((List<?>)arguments).size();
            Object[] coercedArguments = new Object[numOptionParams];
            for (int paramCount = 0; paramCount < numOptionParams; paramCount++) {
                Object argument = arguments.get(paramCount);
                Type paramType = method.getGenericParameterTypes()[paramCount];
                coercedArguments[paramCount] = TypeCoercions.coerce(argument, TypeToken.of(paramType));
            }
            return Maybe.of(accessibleMethod.invoke(instanceOrClazz, coercedArguments));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw Exceptions.propagate(e);
        }
    }
    
    /**
     * Tries to find a multiple-parameter method with each parameter compatible with (can be coerced to) the
     * corresponding argument, and invokes it.
     *
     * @param instance the object to invoke the method on
     * @param methodName the name of the method to invoke
     * @param argument a list of the arguments to the method's parameters.
     * @return the result of the method call, or {@link org.apache.brooklyn.util.guava.Maybe#absent()} if method could not be matched.
     */
    public static Maybe<?> tryFindAndInvokeMultiParameterMethod(Object instance, String methodName, List<?> arguments) {
        Class<?> clazz = instance.getClass();
        Iterable<Method> methods = Iterables.filter(Arrays.asList(clazz.getMethods()), matchMethodByName(methodName));
        if (Iterables.isEmpty(methods)) {
            return Maybe.absent("No method found named '"+methodName+"' on "+clazz.getName());
        }
        return tryFindAndInvokeMultiParameterMethod(instance, methods, arguments);
    }

    /**
     * Tries to find a method with each parameter compatible with (can be coerced to) the corresponding argument, and invokes it.
     *
     * @param instance the object to invoke the method on
     * @param methodName the name of the method to invoke
     * @param argument a list of the arguments to the method's parameters, or a single argument for a single-parameter method.
     * @return the result of the method call, or {@link org.apache.brooklyn.util.guava.Maybe#absent()} if method could not be matched.
     */
    public static Maybe<?> tryFindAndInvokeBestMatchingMethod(Object instance, String methodName, Object argument) {
        if (argument instanceof List) {
            List<?> arguments = (List<?>) argument;

            // ambiguous case: we can't tell if the user is using the multi-parameter syntax, or the single-parameter
            // syntax for a method which takes a List parameter. So we try one, then fall back to the other.
            // Prefer the multi-parameter error message!

            Maybe<?> maybe = tryFindAndInvokeMultiParameterMethod(instance, methodName, arguments);
            if (maybe.isAbsent()) {
                Maybe<?> maybe2 = tryFindAndInvokeSingleParameterMethod(instance, methodName, argument);
                if (maybe2.isPresent()) {
                    maybe = maybe2;
                }
            }
            return maybe;
        } else {
            return tryFindAndInvokeSingleParameterMethod(instance, methodName, argument);
        }
    }
    
    private static Maybe<?> tryCoerce(Object val, Type type) {
        return tryCoerce(val, TypeToken.of(type));
    }
    
    // TODO I [Aled] thought TypeCoercions.tryCoerce would not throw ClassCoercionException, but it does:
    //   TypeCoercions.tryCoerce("not a number", TypeToken.of(Integer.class));
    private static Maybe<?> tryCoerce(Object val, TypeToken<?> type) {
        try {
            return TypeCoercions.tryCoerce(val, type);
        } catch (ClassCoercionException e) {
            return Maybe.absent(e);
        }
    }
}
