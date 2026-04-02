package com.vanvatcorporation.doubleclips.impl;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl2;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class ViewPagerImpl extends ViewPager {

    int lastPosition = 0;

    PagerAdapter pagerAdapter;
    HashMap<String, Integer> pagerMap = new HashMap<>();


    RunnableImpl2 onPageChangeAction = new RunnableImpl2() {
        @Override
        public <T, T2> void runWithParam(T param, T2 param2) {

        }
    };


    public void setupActions(RunnableImpl2 onPageChangeAction)
    {
        this.onPageChangeAction = onPageChangeAction;
    }


    void setupInternalExecutingAction()
    {
        addOnPageChangeListener(new OnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                onPageChangeAction.runWithParam(lastPosition, position);
                lastPosition = position;
            }

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}

            @Override
            public void onPageScrollStateChanged(int state) {}
        });
    }



    public ViewPagerImpl(@NonNull Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setupInternalExecutingAction();
    }

    public ViewPagerImpl(@NonNull Context context) {
        super(context);
        setupInternalExecutingAction();
    }
    public ViewPagerImpl(@NonNull Context context, @NonNull PagerAdapter pagerAdapter) {
        super(context);
        setupInternalExecutingAction();
        setAdapter(pagerAdapter);
        this.pagerAdapter = pagerAdapter;
    }
    public ViewPagerImpl(@NonNull Context context, @NonNull View... views) {
        super(context);
        setupInternalExecutingAction();
        LayoutAdaptor layoutAdaptor = makeAdapter(views);
        setAdapter(layoutAdaptor);
        this.pagerAdapter = layoutAdaptor;
    }




    public void insertView(@NonNull View... views) {
        LayoutAdaptor layoutAdaptor = makeAdapter(views);
        setAdapter(layoutAdaptor);
        this.pagerAdapter = layoutAdaptor;
    }




    public void setPagePointerNames(HashMap<String, Integer> names)
    {
        pagerMap.putAll(names);
    }


    public void setCurrentItem(String item) {
        if(pagerMap == null || pagerMap.get(item) == null) return;
        int itemInt = pagerMap.get(item);
        super.setCurrentItem(itemInt);
    }

    public void setCurrentItem(String item, boolean smoothScroll) {
        if(pagerMap == null || pagerMap.get(item) == null) return;
        int itemInt = pagerMap.get(item);
        super.setCurrentItem(itemInt, smoothScroll);
    }

    @NonNull
    @Contract("_ -> new")
    public static LayoutAdaptor makeAdapter(View... views)
    {
        return new LayoutAdaptor(views);
    }




    public static class LayoutAdaptor extends PagerAdapter
    {
        public ArrayList<View> layoutViews = new ArrayList<>();
        public LayoutAdaptor() {}
        public LayoutAdaptor(ArrayList<View> al)
        {
            layoutViews = al;
        }
        public LayoutAdaptor(View... al)
        {
            layoutViews.addAll(Arrays.asList(al));
        }
        @Override
        public int getCount() {
            if(layoutViews != null)
                return layoutViews.size();
            return 0;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
//            View view = new View(LauncherActivity.this);
//            view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
//            view.add
//            container.addView(view);
            container.addView(layoutViews.get(position));
            return layoutViews.get(position);
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }
}