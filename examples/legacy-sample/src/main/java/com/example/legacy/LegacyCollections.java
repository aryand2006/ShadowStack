package com.example.legacy;

import java.util.Hashtable;
import java.util.Stack;
import java.util.Vector;

/**
 * Classic pre-Java-5 / synchronized-collection patterns that industry
 * modernization tools (OpenRewrite, Sonar) rewrite first.
 */
public class LegacyCollections {

    public String buildMessage(String prefix, String body) {
        StringBuffer buffer = new StringBuffer();
        buffer.append(prefix);
        buffer.append(':');
        buffer.append(body);
        return buffer.toString();
    }

    public Vector<String> legacyList() {
        Vector<String> items = new Vector<String>();
        items.add("a");
        if (items.size() == 0) {
            items.add("fallback");
        }
        return items;
    }

    public Hashtable<String, Integer> legacyMap() {
        Hashtable<String, Integer> map = new Hashtable<String, Integer>();
        map.put("answer", new Integer(42));
        return map;
    }

    public Stack<String> legacyStack() {
        Stack<String> stack = new Stack<String>();
        stack.push("top");
        return stack;
    }

    public boolean matchesRole(String role) throws Exception {
        if (role.indexOf("admin") >= 0) {
            return role.equals("admin");
        }
        String normalized = role.toUpperCase();
        Class<?> type = String.class;
        Object probe = type.newInstance();
        return normalized != null && probe != null;
    }
}
