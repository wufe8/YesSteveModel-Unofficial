package com.eliotlash.mclib.math;

import java.util.HashSet;
import java.util.Set;

public enum Operation {

    // 操作符
    ADD("+", 4) {

        @Override

        public double calculate(double a, double b) {
            return a + b;
        }
    },
    SUB("-", 4) {

        @Override

        public double calculate(double a, double b) {
            return a - b;
        }
    },
    MUL("*", 5) {

        @Override

        public double calculate(double a, double b) {
            return a * b;
        }
    },
    DIV("/", 5) {

        @Override

        public double calculate(double a, double b) {
            /* To avoid any exceptions */
            return a / (b == 0 ? 1 : b);
        }
    },
    MOD("%", 5) {

        @Override

        public double calculate(double a, double b) {
            return a % b;
        }
    },
    POW("^", 6) {

        @Override

        public double calculate(double a, double b) {
            return Math.pow(a, b);
        }
    },
    AND("&&", 1) {

        @Override

        public double calculate(double a, double b) {
            return a != 0 && b != 0 ? 1 : 0;
        }
    },
    OR("||", 0) {

        @Override

        public double calculate(double a, double b) {
            return a != 0 || b != 0 ? 1 : 0;
        }
    },
    LESS("<", 3) {

        @Override

        public double calculate(double a, double b) {
            return a < b ? 1 : 0;
        }
    },
    LESS_THAN("<=", 3) {

        @Override

        public double calculate(double a, double b) {
            return a <= b ? 1 : 0;
        }
    },
    GREATER_THAN(">=", 3) {

        @Override

        public double calculate(double a, double b) {
            return a >= b ? 1 : 0;
        }
    },
    GREATER(">", 3) {

        @Override

        public double calculate(double a, double b) {
            return a > b ? 1 : 0;
        }
    },
    EQUALS("==", 2) {

        @Override

        public double calculate(double a, double b) {
            return equals(a, b) ? 1 : 0;
        }
    },
    NOT_EQUALS("!=", 2) {

        @Override

        public double calculate(double a, double b) {
            return !equals(a, b) ? 1 : 0;
        }
    },
    ASSIGN("=", 5) {
        @Override
        public double calculate(double a, double b) {
            return b;
        }
    };

    public final static Set<String> OPERATORS = new HashSet<String>();

    static {
        for (Operation op : values()) {
            OPERATORS.add(op.sign);
        }
    }

    /**
     * 此操作的字符串化名称
     */
    public final String sign;
    /**
     * 此操作相对于其他操作的优先级
     */
    public final int value;

    Operation(String sign, int value) {
        this.sign = sign;
        this.value = value;
    }

    public static boolean equals(double a, double b) {
        return Math.abs(a - b) < 0.00001;
    }

    /**
     * 根据给定的两个 double 计算值
     */
    public abstract double calculate(double a, double b);
}
