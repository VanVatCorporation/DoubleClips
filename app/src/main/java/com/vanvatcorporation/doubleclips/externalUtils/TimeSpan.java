package com.vanvatcorporation.doubleclips.externalUtils;

import androidx.annotation.NonNull;

public class TimeSpan {
    public static final long TicksPerDay = 864000000000L;
    public static final long TicksPerHour = 36000000000L;
    public static final long TicksPerMillisecond = 10000;
    public static final long TicksPerMinute = 600000000;
    public static final long TicksPerSecond = 10000000;
    public static TimeSpan MaxValue;
    public static TimeSpan MinValue;
    public static TimeSpan Zero;

    public TimeSpan(long ticks) {
        this.ticks = ticks;
    }
    public TimeSpan(int hours, int minutes, int seconds) {
        this.ticks = hours * TicksPerHour + minutes * TicksPerMinute + seconds * TicksPerSecond;
    }

    public TimeSpan(int days, int hours, int minutes, int seconds) {
        this.ticks = days * TicksPerDay + hours * TicksPerHour + minutes * TicksPerMinute + seconds * TicksPerSecond;
    }

    public TimeSpan(int days, int hours, int minutes, int seconds, int milliseconds) {
        this.ticks = days * TicksPerDay + hours * TicksPerHour + minutes * TicksPerMinute + seconds * TicksPerSecond + milliseconds * TicksPerMillisecond;
    }

    public double TotalMilliseconds() { return (double) ticks / TicksPerMillisecond; }
    public double TotalHours() { return (double) ticks / TicksPerHour; }
    public double TotalDays() { return (double) ticks / TicksPerDay; }
    public long Ticks() { return ticks; }
    public int Seconds() { return (int) (ticks / TicksPerSecond); }
    public int Minutes() { return (int) (ticks / TicksPerMinute); }
    public int Milliseconds() { return (int) (ticks / TicksPerMillisecond); }
    public int Hours() { return (int) (ticks / TicksPerHour); }
    public int Days() { return (int) (ticks / TicksPerDay); }
    public double TotalMinutes() { return (double) ticks / TicksPerMinute; }
    public double TotalSeconds() { return (double) ticks / TicksPerSecond; }

    public static int compare(TimeSpan t1, TimeSpan t2) {
        return 0;
    }

    public static boolean equals(TimeSpan t1, TimeSpan t2) {
        return t1.ticks == t2.ticks;
    }

    public static TimeSpan FromDays(double value) {
        return new TimeSpan((long) (value * TicksPerDay));
    }
    public static TimeSpan FromHours(double value) {
        return new TimeSpan((long) (value * TicksPerHour));
    }
    public static TimeSpan FromMilliseconds(double value) {
        return new TimeSpan((long) (value * TicksPerMillisecond));
    }
    public static TimeSpan FromMinutes(double value) {
        return new TimeSpan((long) (value * TicksPerMinute));
    }
    public static TimeSpan FromSeconds(double value) {
        return new TimeSpan((long) (value * TicksPerSecond));
    }

    public static TimeSpan FromTicks(long value) {
        return new TimeSpan(value);
    }

    //    public static TimeSpan Parse(string input, IFormatProvider formatProvider);
//    public static TimeSpan Parse(string s);
//    public static TimeSpan Parse(ReadOnlySpan<char> input, IFormatProvider formatProvider = null);
//    public static TimeSpan ParseExact(string input, string[] formats, IFormatProvider formatProvider, TimeSpanStyles styles);
//    public static TimeSpan ParseExact(string input, string format, IFormatProvider formatProvider, TimeSpanStyles styles);
//    public static TimeSpan ParseExact(string input, string format, IFormatProvider formatProvider);
//    public static TimeSpan ParseExact(ReadOnlySpan<char> input, string[] formats, IFormatProvider formatProvider, TimeSpanStyles styles = TimeSpanStyles.None);
//    public static TimeSpan ParseExact(ReadOnlySpan<char> input, ReadOnlySpan<char> format, IFormatProvider formatProvider, TimeSpanStyles styles = TimeSpanStyles.None);
//    public static TimeSpan ParseExact(string input, string[] formats, IFormatProvider formatProvider);
//    public static bool TryParse(ReadOnlySpan<char> s, out TimeSpan result);
//    public static bool TryParse(string input, IFormatProvider formatProvider, out TimeSpan result);
//    public static bool TryParse(string s, out TimeSpan result);
//    public static bool TryParse(ReadOnlySpan<char> input, IFormatProvider formatProvider, out TimeSpan result);
//    public static bool TryParseExact(string input, string format, IFormatProvider formatProvider, TimeSpanStyles styles, out TimeSpan result);
//    public static bool TryParseExact(ReadOnlySpan<char> input, string[] formats, IFormatProvider formatProvider, out TimeSpan result);
//    public static bool TryParseExact(ReadOnlySpan<char> input, string[] formats, IFormatProvider formatProvider, TimeSpanStyles styles, out TimeSpan result);
//    public static bool TryParseExact(ReadOnlySpan<char> input, ReadOnlySpan<char> format, IFormatProvider formatProvider, out TimeSpan result);
//    public static bool TryParseExact(ReadOnlySpan<char> input, ReadOnlySpan<char> format, IFormatProvider formatProvider, TimeSpanStyles styles, out TimeSpan result);
//    public static bool TryParseExact(string input, string[] formats, IFormatProvider formatProvider, TimeSpanStyles styles, out TimeSpan result);
//    public static bool TryParseExact(string input, string[] formats, IFormatProvider formatProvider, out TimeSpan result);
//    public static bool TryParseExact(string input, string format, IFormatProvider formatProvider, out TimeSpan result);
    public TimeSpan Add(TimeSpan ts)
    {
        this.ticks += ts.ticks;
        return this;
    }

    public int CompareTo(Object value) {
        return 0;
    }

    public int CompareTo(TimeSpan value) {
        return 0;
    }

    public TimeSpan Divide(double divisor) {
        return null;
    }

    public double Divide(TimeSpan ts) {
        return 0;
    }

    public TimeSpan Duration() {
        return null;
    }

    public boolean equals(Object value)
    {
        return this == value;
    }
    public boolean equals(TimeSpan obj)
    {
        return this.ticks == obj.ticks;
    }
//    public override int GetHashCode();
    public TimeSpan Multiply(double factor)
    {
        this.ticks *= factor;
        return this;
    }
//    public TimeSpan Negate();
    public TimeSpan Subtract(TimeSpan ts)
    {
        this.ticks -= ts.ticks;
        return this;
    }
//    public String ToString(String format, IFormatProvider formatProvider)
//    {
//        return super.toString();
//    }
    public String toString(String format)
    {
        return super.toString();
    }
    @NonNull
    public String toString()
    {
        return super.toString();
    }
//    public bool TryFormat(Span<char> destination, out int charsWritten, ReadOnlySpan<char> format = default, IFormatProvider formatProvider = null);

//    public static TimeSpan operator +(TimeSpan t);
//    public static TimeSpan operator +(TimeSpan t1, TimeSpan t2);
//    public static TimeSpan operator -(TimeSpan t);
//    public static TimeSpan operator -(TimeSpan t1, TimeSpan t2);
//    public static TimeSpan operator *(double factor, TimeSpan timeSpan);
//    public static TimeSpan operator *(TimeSpan timeSpan, double factor);
//    public static TimeSpan operator /(TimeSpan timeSpan, double divisor);
//    public static double operator /(TimeSpan t1, TimeSpan t2);
//    public static bool operator ==(TimeSpan t1, TimeSpan t2);
//    public static bool operator !=(TimeSpan t1, TimeSpan t2);
//    public static bool operator <(TimeSpan t1, TimeSpan t2);
//    public static bool operator >(TimeSpan t1, TimeSpan t2);
//    public static bool operator <=(TimeSpan t1, TimeSpan t2);
//    public static bool operator >=(TimeSpan t1, TimeSpan t2);


    /**
     * Java Implementation
     */
    long ticks = 0;
}