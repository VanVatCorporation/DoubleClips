package com.vanvatcorporation.doubleclips.externalUtils;

public class LeanTween {
	/***
	 * @param t is currentTime
	 * @param b is start point to lean from
	 * @param c is finish point to lean to
	 * @param d is durationTime that currentTime will based on, also called maxCurrentTime
	 */
	public static float easeInOutExpo(float t, float b, float c, float d) {
	    if (t == 0)
	        return b;
	    if (t == d)
	        return b + c;
	    if ((t /= d / 2) < 1)
	        return c / 2 * (float) Math.pow(2, 10 * (t - 1)) + b;
	    return c / 2 * (-(float) Math.pow(2, -10 * --t) + 2) + b;
	}
	
	
	public static float easeLinear(float t, float b, float c, float d) {
	    return b + c * (t / d);
	}
	
	

	/***
	 * @param t is currentTime
	 * @param d is durationTime that currentTime will based on, also called maxCurrentTime
	 * @param a is start point to lerp from
	 * @param b is finish point to lerp to
	 */
	public static float easeLerpLinear(float t, float a, float b, float d) {
	    return a + (b - a) * (t / d);
	}
}
