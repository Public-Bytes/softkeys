package dev.softkeys.ios;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/**
 * The look of the SoftKeys app screens: colors for light and dark mode, a toggle switch, a
 * slider, text fields, and bottom sheets that replace Android's default dialogs. The style
 * follows the keyboard's iOS-inspired design: grouped rounded cards, a green switch, and
 * sheets that slide up from the bottom.
 */
final class Ui {
    private Ui(){}

    /** Colors for one appearance. */
    static final class Palette {
        final boolean dark;
        final int background,card,ink,muted,separator,field,pressed,accent,green,red,track,knob;
        Palette(boolean dark){
            this.dark=dark;
            background=dark?0xff000000:0xfff2f1f6;
            card=dark?0xff1c1c1e:0xffffffff;
            ink=dark?0xfff5f5f7:0xff1c1c1e;
            muted=dark?0xff98989f:0xff86868b;
            separator=dark?0xff38383a:0xffe3e3e8;
            field=dark?0xff2c2c2e:0xfff2f2f7;
            pressed=dark?0xff2c2c2e:0xffe8e8ed;
            accent=dark?0xff0a84ff:0xff007aff;
            green=dark?0xff30d158:0xff34c759;
            red=dark?0xffff453a:0xffff3b30;
            track=dark?0xff39393d:0xffe9e9eb;
            knob=0xffffffff;
        }
        static Palette of(Context context){
            int mode=context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK;
            return new Palette(mode==Configuration.UI_MODE_NIGHT_YES);
        }
    }

    static int dp(Context context,float n){return (int)(n*context.getResources().getDisplayMetrics().density+.5f);}

    static GradientDrawable rounded(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}

    /** A background that shows the pressed color while a finger is down. */
    static StateListDrawable pressable(int normal,int pressed,float radius){
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},rounded(pressed,radius));
        states.addState(new int[]{},rounded(normal,radius));
        return states;
    }

    /** Colors the status and navigation bars to match the page. */
    static void bars(Activity activity,Palette p){
        Window window=activity.getWindow();
        window.setStatusBarColor(p.background);window.setNavigationBarColor(p.background);
        int flags=p.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** A rounded, filled text field with no underline and a cursor in the accent color. */
    static void field(EditText edit,Palette p){
        Context c=edit.getContext();
        edit.setBackground(rounded(p.field,dp(c,12)));
        edit.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));
        edit.setTextColor(p.ink);edit.setHintTextColor(p.muted);
        edit.setHighlightColor((p.accent&0x00ffffff)|0x55000000);
        if(Build.VERSION.SDK_INT>=29){
            GradientDrawable cursor=rounded(p.accent,dp(c,1));cursor.setSize(dp(c,2),dp(c,20));
            edit.setTextCursorDrawable(cursor);
        }
    }

    /** A thin slider with a round white handle. */
    static void slider(SeekBar bar,Palette p){
        Context c=bar.getContext();
        int height=dp(c,4);
        GradientDrawable back=rounded(p.track,height/2f);
        GradientDrawable fill=rounded(p.accent,height/2f);
        LayerDrawable track=new LayerDrawable(new android.graphics.drawable.Drawable[]{back,new ClipDrawable(fill,Gravity.START,ClipDrawable.HORIZONTAL)});
        track.setId(0,android.R.id.background);track.setId(1,android.R.id.progress);
        for(int i=0;i<2;i++){track.setLayerHeight(i,height);track.setLayerGravity(i,Gravity.CENTER_VERTICAL|Gravity.FILL_HORIZONTAL);}
        bar.setProgressDrawable(track);
        GradientDrawable shadow=new GradientDrawable();shadow.setShape(GradientDrawable.OVAL);shadow.setColor(0x26000000);shadow.setSize(dp(c,28),dp(c,28));
        GradientDrawable knob=new GradientDrawable();knob.setShape(GradientDrawable.OVAL);knob.setColor(p.knob);knob.setSize(dp(c,26),dp(c,26));
        if(!p.dark)knob.setStroke(Math.max(1,dp(c,.5f)),0x14000000);
        LayerDrawable thumb=new LayerDrawable(new android.graphics.drawable.Drawable[]{shadow,knob});
        thumb.setLayerInset(1,dp(c,1),0,dp(c,1),dp(c,2));
        bar.setThumb(thumb);bar.setSplitTrack(false);bar.setBackground(null);
        bar.setThumbOffset(dp(c,2));
    }

    /** An on-off switch drawn as a rounded track with a sliding white knob. */
    static final class Toggle extends View {
        interface Listener{void changed(boolean checked);}
        private final Palette palette;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect=new RectF();
        private boolean checked;
        private float position;
        private ValueAnimator animator;
        private Listener listener;

        Toggle(Context context,Palette palette){
            super(context);this.palette=palette;
            setLayerType(LAYER_TYPE_SOFTWARE,null);
            setFocusable(true);setClickable(true);
            setOnClickListener(v->setChecked(!checked,true));
            setAccessibilityDelegate(new AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                    super.onInitializeAccessibilityNodeInfo(host,info);
                    info.setClassName("android.widget.Switch");info.setCheckable(true);info.setChecked(checked);
                }
                @Override public void onInitializeAccessibilityEvent(View host,AccessibilityEvent event){
                    super.onInitializeAccessibilityEvent(host,event);event.setClassName("android.widget.Switch");event.setChecked(checked);
                }
            });
        }
        void setListener(Listener listener){this.listener=listener;}
        boolean isChecked(){return checked;}
        void toggle(){setChecked(!checked,true);}
        /** Changes the state. animate is false when the screen is first built. */
        void setChecked(boolean value,boolean animate){
            if(value==checked&&animator==null){position=value?1:0;invalidate();return;}
            boolean changed=value!=checked;
            checked=value;
            if(animator!=null)animator.cancel();
            if(animate){
                animator=ValueAnimator.ofFloat(position,value?1:0);animator.setDuration(220);animator.setInterpolator(new DecelerateInterpolator());
                animator.addUpdateListener(a->{position=(float)a.getAnimatedValue();invalidate();});
                animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){animator=null;}});
                animator.start();
            }else{position=value?1:0;invalidate();}
            if(changed){
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                if(listener!=null)listener.changed(value);
            }
        }
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(dp(getContext(),51),dp(getContext(),31));}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),r=h/2;
            paint.setColor(blend(palette.track,palette.green,position));
            rect.set(0,0,w,h);canvas.drawRoundRect(rect,r,r,paint);
            float knob=r-dp(getContext(),2),cx=r+(w-2*r)*position;
            paint.setColor(palette.knob);paint.setShadowLayer(dp(getContext(),2),0,dp(getContext(),1),0x40000000);
            canvas.drawCircle(cx,r,knob,paint);paint.clearShadowLayer();
        }
    }

    static int blend(int a,int b,float t){
        t=Math.max(0,Math.min(1,t));
        int ar=Color.red(a),ag=Color.green(a),ab=Color.blue(a),br=Color.red(b),bg=Color.green(b),bb=Color.blue(b);
        return Color.rgb(Math.round(ar+(br-ar)*t),Math.round(ag+(bg-ag)*t),Math.round(ab+(bb-ab)*t));
    }

    /**
     * A sheet that slides up from the bottom, in place of an Android dialog. It has a card
     * with an optional title, message, and custom content, the actions below them, and a
     * separate Cancel button.
     */
    static final class Sheet {
        enum Style{NORMAL,BOLD,DESTRUCTIVE}
        private final Activity activity;
        private final Palette p;
        private final Dialog dialog;
        private final LinearLayout card,actions;
        private Runnable cancel;
        private String cancelLabel="Cancel";
        private boolean hasActions;

        Sheet(Activity activity,Palette palette){
            this.activity=activity;this.p=palette;
            dialog=new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(rounded(p.card,Ui.dp(activity,16)));card.setClipToOutline(true);
            actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);
        }
        private int dp(float n){return Ui.dp(activity,n);}
        Sheet title(String text){
            TextView t=new TextView(activity);t.setText(text);t.setTextSize(15);t.setTextColor(p.ink);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            t.setGravity(Gravity.CENTER);t.setPadding(dp(20),dp(18),dp(20),dp(4));card.addView(t);return this;
        }
        Sheet message(String text){
            TextView t=new TextView(activity);t.setText(text);t.setTextSize(13.5f);t.setTextColor(p.muted);t.setGravity(Gravity.CENTER);t.setLineSpacing(0,1.1f);
            t.setPadding(dp(20),dp(4),dp(20),dp(16));card.addView(t);return this;
        }
        /** Custom content, such as a text field, below the message. */
        Sheet content(View view){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(16),0,dp(16),dp(16));card.addView(view,lp);return this;
        }
        /** A button row. The sheet closes before the action runs. */
        Sheet action(String label,Style style,Runnable run){
            separator(actions);
            TextView b=button(label,style==Style.DESTRUCTIVE?p.red:p.accent,style==Style.BOLD);
            b.setOnClickListener(v->{dialog.dismiss();if(run!=null)run.run();});
            actions.addView(b,new LinearLayout.LayoutParams(-1,dp(56)));hasActions=true;return this;
        }
        /** A list of choices with a checkmark beside the selected one. */
        Sheet choices(String[] labels,int selected,IntConsumer choose){
            for(int i=0;i<labels.length;i++){
                separator(actions);
                LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(20),0,dp(20),0);
                row.setBackground(pressable(p.card,p.pressed,0));
                TextView t=new TextView(activity);t.setText(labels[i]);t.setTextSize(17);t.setTextColor(p.ink);
                row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
                TextView check=new TextView(activity);check.setText(i==selected?"✓":"");check.setTextSize(19);check.setTextColor(p.accent);check.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
                row.addView(check);
                final int index=i;
                row.setContentDescription(labels[i]+(i==selected?", selected":""));
                row.setOnClickListener(v->{dialog.dismiss();choose.accept(index);});
                actions.addView(row,new LinearLayout.LayoutParams(-1,dp(54)));
            }
            hasActions=true;return this;
        }
        Sheet onCancel(Runnable run){cancel=run;return this;}
        Sheet cancelLabel(String label){cancelLabel=label;return this;}
        private void separator(LinearLayout parent){
            if(parent.getChildCount()==0&&parent==actions&&card.getChildCount()==0)return;
            View line=new View(activity);line.setBackgroundColor(p.separator);parent.addView(line,new LinearLayout.LayoutParams(-1,Math.max(1,dp(.6f))));
        }
        private TextView button(String label,int color,boolean bold){
            TextView b=new TextView(activity);b.setText(label);b.setTextSize(18);b.setTextColor(color);b.setGravity(Gravity.CENTER);
            b.setTypeface(bold?Typeface.create("sans-serif-medium",Typeface.NORMAL):Typeface.DEFAULT);
            b.setBackground(pressable(p.card,p.pressed,0));b.setFocusable(true);
            return b;
        }
        Dialog show(){
            if(hasActions)card.addView(actions);
            LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(10),0,dp(10),dp(10));
            // The card scrolls when it is taller than the screen allows, and leaves room for Cancel.
            ScrollView scroll=new ScrollView(activity){
                @Override protected void onMeasure(int w,int h){
                    int cap=activity.getResources().getDisplayMetrics().heightPixels-dp(150);
                    super.onMeasure(w,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));
                }
            };
            scroll.addView(card);scroll.setVerticalScrollBarEnabled(false);
            root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
            TextView close=button(cancelLabel,p.accent,true);
            close.setBackground(pressable(p.card,p.pressed,dp(16)));
            close.setOnClickListener(v->dialog.cancel());
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.topMargin=dp(8);root.addView(close,lp);
            root.setGravity(Gravity.BOTTOM);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                int bottom=insets.getSystemWindowInsetBottom(),top=insets.getSystemWindowInsetTop();
                v.setPadding(dp(10),top+dp(10),dp(10),bottom+dp(10));
                return insets;
            });
            dialog.setContentView(root);
            dialog.setOnCancelListener(d->{if(cancel!=null)cancel.run();});
            Window window=dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(.35f);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);
            else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            window.setNavigationBarColor(Color.TRANSPARENT);
            // Tapping above the card closes the sheet, like Cancel.
            root.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP&&e.getY()<scroll.getTop()){dialog.cancel();return true;}return e.getY()<scroll.getTop();});
            dialog.show();
            return dialog;
        }
    }
}
