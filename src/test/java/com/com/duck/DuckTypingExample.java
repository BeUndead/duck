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
 * See the License for thse specific language governing permissions and
 * limitations under the License.
 */

package com.com.duck;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DuckTypingExample {

    // region Internal
    private static class WingsException extends Exception {}
    private static class NoWingsException extends WingsException {}

    public interface Bird {
        default void fly() { // Supports default methods
            System.out.println("Flying");
        };
    }

    public interface Duck extends Bird { // Supports super interfaces
        void quack(int volume);

        double wingSpan() throws WingsException;
    }

    private static class PseudoBird { // Supports methods from super classes
        private void quack(final int volume) { // Supports private methods
            System.out.println("Quacking at " + volume + " db");
        }
    }

    private static final class PseudoDuck extends PseudoBird { // Supports final classes

        private double wingSpan() throws NoWingsException { // Supports valid Exceptions in signatures
            return 1.234D;
        }
    }
    // endregion Internal


    @Test
    void testDuckTyping() throws WingsException {
        final Duck duck = DuckTyper.duckType(new PseudoDuck(), Duck.class);

        duck.fly();
        duck.quack(20 /*db*/);
        Assertions.assertEquals(1.234D, duck.wingSpan());
    }
}
