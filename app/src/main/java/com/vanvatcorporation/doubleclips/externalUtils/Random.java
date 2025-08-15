package com.vanvatcorporation.doubleclips.externalUtils;

public class Random {
	static java.util.Random rand = new java.util.Random();

	/**
	 * Random a double value
	 * @param min Inclusive Minimum number the Random can get
	 * @param max Inclusive Maximum number the Random can get
	 * @return The random number
	 */
	public static double Range(double min, double max)
	{
		return Math.random() * (max - min) + min;
	}

	/**
	 * Random a float value
	 * @param min Inclusive Minimum number the Random can get
	 * @param max Inclusive Maximum number the Random can get
	 * @return The random number
	 */
	public static float Range(float min, float max)
	{
		return (float) (Math.random() * (max - min) + min);
	}

	/**
	 * Random an integer value
	 * @param min Inclusive Minimum number the Random can get
	 * @param max Inclusive Maximum number the Random can get
	 * @return The random number
	 */
public static int Range(int min, int max) { return rand.nextInt(max + 1 - min) + min;}
}
