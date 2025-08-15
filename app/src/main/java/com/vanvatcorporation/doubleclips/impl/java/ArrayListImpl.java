package com.vanvatcorporation.doubleclips.impl.java;

import java.util.ArrayList;
import java.util.Arrays;

public class ArrayListImpl<T> extends ArrayList<T> {
    public RunnableImpl onAddListener;
    public RunnableImpl onRemoveListener;
    public RunnableImpl onClearListener;
    public ArrayList<RunnableImpl> onAddListeners = new ArrayList<>();
    public ArrayList<RunnableImpl> onRemoveListeners = new ArrayList<>();
    public ArrayList<RunnableImpl> onClearListeners = new ArrayList<>();

    public int count(){return size();}



    public ArrayListImpl()
    {
        super();
    }
    @SafeVarargs
    public ArrayListImpl(T... data)
    {
        super(Arrays.asList(data));
    }
//    public ArrayListImpl(T[] data)
//    {
//        super(Arrays.asList(data));
//    }
    public ArrayListImpl(int initialCapacity)
    {
        super(initialCapacity);
    }





    @Override
    public boolean add(T t) {
        boolean superReturn = super.add(t);
        if(onAddListener != null)
            onAddListener.runWithParam(t);
        if(onAddListeners != null)
            for (RunnableImpl run : onAddListeners) {
                run.runWithParam(t);
            }
        return superReturn;
    }


    public T removeImpl(T t) {
        super.remove(t);
        if(onRemoveListener != null)
            onRemoveListener.runWithParam(t);
        if(onRemoveListeners != null)
            for (RunnableImpl run : onRemoveListeners) {
                run.runWithParam(t);
            }
        return t;
    }

    @Override
    public T remove(int index) {
        T superReturn = super.remove(index);
        if(onRemoveListener != null)
            onRemoveListener.runWithParam(index);
        if(onRemoveListeners != null)
            for (RunnableImpl run : onRemoveListeners) {
                run.runWithParam(index);
            }
        return superReturn;
    }
    @Override
    public void clear() {
        super.clear();
        if(onClearListener != null)
            onClearListener.runWithParam(null);
        if(onClearListeners != null)
            for (RunnableImpl run : onClearListeners) {
                run.runWithParam(null);
            }
    }

//    @NonNull
//    public T[] toArray() {
//        return super.toArray();
//    }



    //region SpecificGet

    public Object getObject(int index)
    {
        return get(index);
    }
    public int getIndex(T object)
    {
        return indexOf(object);
    }

    //endregion

    //region Insert Range

    public void insertRange(int index, T[] range)
    {
        addAll(index, Arrays.asList(range));
    }
    public void insertRange(int index, ArrayListImpl<T> range)
    {
        addAll(index, range);
    }
    public void insertRange(int index, ArrayList<T> range)
    {
        addAll(index, range);
    }

    //endregion


    //region Add Range

    public void addRange(T[] range)
    {
        addAll(Arrays.asList(range));
    }
    public void addRange(ArrayListImpl<T> range)
    {
        addAll(range);
    }
    public void addRange(ArrayList<T> range)
    {
        addAll(range);
    }


    @SafeVarargs
    public final void addRange(T[]... ranges)
    {
        for (T[] range : ranges) {
            addAll(Arrays.asList(range));
        }
    }
    @SafeVarargs
    public final void addRange(ArrayListImpl<T>... ranges)
    {
        for (ArrayListImpl<T> range : ranges) {
            addAll(range);
        }
    }
    @SafeVarargs
    public final void addRange(ArrayList<T>... ranges)
    {
        for (ArrayList<T> range : ranges) {
            addAll(range);
        }
    }
    //endregion


    public ArrayListImpl<T> getRange(int startIndex, int length) {
        ArrayListImpl<T> ts = new ArrayListImpl<>();
        for (int i = startIndex; i < length; i++) {
            ts.add(get(i));
        }
        return ts;
    }



}
