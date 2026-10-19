package com.marketflow.chat.domain;

import java.net.IDN;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message text rules.
 *
 * <p><b>Shape</b>: trimmed, 1..maxLength characters, no control characters except newline and tab. Messages are
 * plain text: they are stored and delivered verbatim and every client renders them as text (React escapes them),
 * so markup in a message can never execute. Nothing is "sanitised" by rewriting.
 *
 * <p><b>Off-platform contact</b>: a marketplace loses buyer protection (and its commission) when a deal moves to
 * a phone call or a direct payment. The policy refuses messages containing phone numbers, e-mail addresses
 * (including "name at domain dot com"), Indian UPI ids, {@code upi://} links and links to payment or messaging
 * services. Order and tracking numbers in SYSTEM messages are not checked: those are written by the platform.
 */
public final class ContentPolicy {

    public enum Violation { PHONE_NUMBER, EMAIL_ADDRESS, PAYMENT_LINK }

    public record Result(String text, Set<Violation> violations) {
        public boolean allowed() {
            return violations.isEmpty();
        }
    }

    /** A run of digits with the separators people use in phone numbers; 7-15 digits counts as a number. */
    private static final Pattern PHONE = Pattern.compile("(?<![\\w/])\\+?\\(?\\d[\\d\\s().\\-]{5,22}\\d(?![\\w/])");
    private static final Pattern PLAIN_AT = Pattern.compile(
            "[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)*");
    /** "name(at)mail.com", "name [at] mail [dot] com" */
    private static final Pattern BRACKETED_AT = Pattern.compile(
            "[\\p{L}\\p{N}._%+\\-]+\\s*[(\\[{]\\s*at\\s*[)\\]}]\\s*[\\p{L}\\p{N}\\-]+"
                    + "(?:\\s*(?:\\.|[(\\[{]\\s*dot\\s*[)\\]}])\\s*[\\p{L}\\p{N}\\-]+)+",
            Pattern.CASE_INSENSITIVE);
    /** "name at mail dot com": only with a spelled-out "dot", so ordinary sentences with "at" do not match */
    private static final Pattern SPELLED_AT = Pattern.compile(
            "[\\p{L}\\p{N}._%+\\-]+\\s+at\\s+[\\p{L}\\p{N}\\-]+(?:\\s*(?:\\(dot\\)|\\[dot\\]|\\s+dot\\s+)\\s*[\\p{L}\\p{N}\\-]+)+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** UPI virtual payment addresses: name@okhdfcbank, name@ybl, name@paytm ... */
    private static final Pattern UPI_ID = Pattern.compile(
            "[\\w.\\-]{2,}@(?:ok\\w+|upi|ybl|ibl|axl|paytm|apl|yapl|ptyes|ptaxis|pthdfc|ptsbi|icici|sbi|hdfcbank|axisbank|kotak|"
                    + "jupiteraxis|fbl|waaxis|wahdfcbank|waicici|wasbi|freecharge|airtel|jio|slice|naviaxis|abfspay)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UPI_LINK = Pattern.compile("\\bupi://", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_HOST = Pattern.compile(
            "(?:https?://)?(?:www\\.)?((?:[\\p{L}\\p{N}\\-]+\\.)+[\\p{L}]{2,})(?:[/:?#]\\S*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private final int maxLength;
    private final List<String> blockedHosts;

    public ContentPolicy(int maxLength, List<String> blockedHosts) {
        this.maxLength = maxLength;
        this.blockedHosts = blockedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
    }

    /** Validates shape; throws {@link IllegalArgumentException} with a user-facing reason. */
    public String normaliseText(String raw, boolean required) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").strip();
        if (text.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("Write a message first");
            }
            return null;
        }
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw new IllegalArgumentException("Messages can be at most " + maxLength + " characters");
        }
        if (CONTROL.matcher(text).find()) {
            throw new IllegalArgumentException("The message contains characters that cannot be sent");
        }
        return text;
    }

    public Result check(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, Set.of());
        }
        Set<Violation> found = EnumSet.noneOf(Violation.class);
        if (containsPhone(text)) {
            found.add(Violation.PHONE_NUMBER);
        }
        if (containsEmail(text)) {
            found.add(Violation.EMAIL_ADDRESS);
        }
        if (UPI_ID.matcher(text).find() || UPI_LINK.matcher(text).find() || containsBlockedHost(text)) {
            found.add(Violation.PAYMENT_LINK);
        }
        return new Result(text, found);
    }

    private static boolean containsEmail(String text) {
        Matcher m = PLAIN_AT.matcher(text);
        while (m.find()) {
            String candidate = m.group();
            boolean hasDomainDot = candidate.substring(candidate.indexOf('@')).contains(".");
            if (hasDomainDot && !UPI_ID.matcher(candidate).matches()) {
                return true;
            }
        }
        return BRACKETED_AT.matcher(text).find() || SPELLED_AT.matcher(text).find();
    }

    private static boolean containsPhone(String text) {
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String candidate = m.group();
            long digits = candidate.chars().filter(Character::isDigit).count();
            if (digits >= 7 && digits <= 15 && !looksLikeDecimalAmount(candidate)
                    && !ISO_DATE.matcher(candidate.strip()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** "1,299.00" or "12.50" style prices are not phone numbers. */
    private static boolean looksLikeDecimalAmount(String s) {
        return s.matches("\\d{1,3}(?:[.,]\\d{3})*[.,]\\d{2}");
    }

    private boolean containsBlockedHost(String text) {
        List<String> hosts = new ArrayList<>();
        Matcher m = LINK_HOST.matcher(text);
        while (m.find()) {
            hosts.add(toAscii(m.group(1)));
        }
        for (String host : hosts) {
            for (String blocked : blockedHosts) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String toAscii(String host) {
        try {
            return IDN.toASCII(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return host.toLowerCase(Locale.ROOT);
        }
    }
}
