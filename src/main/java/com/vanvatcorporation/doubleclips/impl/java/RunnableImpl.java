package com.vanvatcorporation.doubleclips.impl.java;

public interface RunnableImpl//<T> //extends Runnable
{
//    Object[] objects;
//    public <T> RunnableImpl(T... objects)
//    {
//        this.objects = objects;
//    }
//    @Override
//    public void run();
    <T> void runWithParam(T param);
    //void runWithParam(int param);
}
