package dev.softkeys.ios;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns spoken numbers into digits for the Clean dictation style.
 *
 * Speech models write numbers the way they sound, such as "a hundred and twenty pounds".
 * This class rewrites them as "120 pounds". A single number from zero to nine stays a word,
 * as in "one time" or "the two of us", unless a unit such as "percent" or "pounds" follows it.
 * It also writes phone numbers, years, clock times, heights, money, percentages, decimals,
 * and ordinals such as "21st". The class uses no Android classes.
 */
final class DictationNumbers {
    private DictationNumbers(){}

    private static final Map<String,Integer> SMALL=new HashMap<>(),TENS=new HashMap<>(),ORDINAL=new HashMap<>();
    private static final Map<String,Long> SCALE=new HashMap<>();
    static{
        String[] small="zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split(" ");
        for(int i=0;i<small.length;i++)SMALL.put(small[i],i);
        String[] tens="twenty thirty forty fifty sixty seventy eighty ninety".split(" ");
        for(int i=0;i<tens.length;i++)TENS.put(tens[i],20+10*i);
        String[] ordinal="first second third fourth fifth sixth seventh eighth ninth tenth eleventh twelfth thirteenth fourteenth fifteenth sixteenth seventeenth eighteenth nineteenth".split(" ");
        for(int i=0;i<ordinal.length;i++)ORDINAL.put(ordinal[i],i+1);
        String[] tensOrdinal="twentieth thirtieth fortieth fiftieth sixtieth seventieth eightieth ninetieth".split(" ");
        for(int i=0;i<tensOrdinal.length;i++)ORDINAL.put(tensOrdinal[i],20+10*i);
        SCALE.put("thousand",1_000L);SCALE.put("million",1_000_000L);SCALE.put("billion",1_000_000_000L);
    }
    /** Words after a number that make even a number from zero to nine a quantity. */
    private static final Set<String> UNITS=Set.of("percent","dollars","dollar","bucks","cents","cent","pounds","pound","lbs","ounces",
        "kilos","kilograms","kg","grams","miles","mile","kilometers","km","meters","feet","foot","inches","inch","degrees","mph",
        "gb","mb","tb","gigs","minutes","minute","hours","hour","seconds","days","weeks","months","years","am","pm","a.m","p.m","o'clock");
    /** Units after which "one" stays a word, as in "one day" or "one time". */
    private static final Set<String> TIME_UNITS=Set.of("minute","hour","seconds","days","weeks","months","years","minutes","hours");
    /** Words before a clock time, as in "at three thirty". */
    private static final Set<String> BEFORE_TIME=Set.of("at","by","until","till","til","around","from","before","after","since","about");

    /** A number read from the tokens: its value and the index after its last word. */
    private record Read(long value,int end,int words){}

    private static String lower(String token){return token.toLowerCase(Locale.ROOT);}
    private static String word(List<String> tokens,int i){return i>=0&&i<tokens.size()?lower(tokens.get(i)):"";}
    private static boolean digitWord(String w){Integer v=SMALL.get(w);return v!=null&&v<10;}

    static void apply(List<String> tokens){
        splitHyphens(tokens);
        for(int i=0;i<tokens.size();i++){
            String w=word(tokens,i);
            if(!SMALL.containsKey(w)&&!TENS.containsKey(w)&&!ORDINAL.containsKey(w)&&!(w.equals("a")&&scaleNext(tokens,i+1)))continue;
            int end=time(tokens,i);
            if(end<0)end=phone(tokens,i);
            if(end<0)end=year(tokens,i);
            if(end<0)end=ordinal(tokens,i);
            if(end<0)end=quantity(tokens,i);
        }
    }

    /** "twenty-five" becomes "twenty" and "five", so it reads like the spaced form. */
    private static void splitHyphens(List<String> tokens){
        for(int i=0;i<tokens.size();i++){
            String t=tokens.get(i);
            if(t.indexOf('-')<=0)continue;
            String[] parts=lower(t).split("-");
            boolean numeric=parts.length==2&&TENS.containsKey(parts[0])&&(digitWord(parts[1])||ORDINAL.containsKey(parts[1]));
            if(!numeric)continue;
            tokens.set(i,parts[0]);tokens.add(i+1,parts[1]);
        }
    }

    private static boolean scaleNext(List<String> tokens,int i){String w=word(tokens,i);return w.equals("hundred")||SCALE.containsKey(w);}

    /** Reads a whole number such as "a hundred and fifty" or "two thousand twenty six". */
    private static Read cardinal(List<String> tokens,int i){
        long total=0,current=0;int j=i,words=0;
        // 0 start, 1 ones or teen, 2 tens, 3 hundred, 4 thousand or larger
        int last=0;
        if(word(tokens,j).equals("a")&&scaleNext(tokens,j+1)){current=1;last=1;j++;words++;}
        while(j<tokens.size()){
            String w=word(tokens,j);
            if(SMALL.containsKey(w)){
                int v=SMALL.get(w);
                if(last==1||last==2&&v>=10)break;
                if(last==2&&current%10!=0)break;
                current+=v;last=1;
            }else if(TENS.containsKey(w)){
                if(last==1||last==2||current%100!=0)break;
                current+=TENS.get(w);last=2;
            }else if(w.equals("hundred")){
                if(last!=1&&last!=2||current>=100||current==0)break;
                current*=100;last=3;
            }else if(SCALE.containsKey(w)){
                if(last==0||last==4)break;
                total+=current*SCALE.get(w);current=0;last=4;
            }else if(w.equals("and")&&(last==3||last==4)&&(SMALL.containsKey(word(tokens,j+1))||TENS.containsKey(word(tokens,j+1)))){
                j++;words++;continue;
            }else break;
            j++;words++;
        }
        if(words==0||last==0)return null;
        // A trailing "and" is not part of the number.
        while(j>i&&word(tokens,j-1).equals("and")){j--;words--;}
        return new Read(total+current,j,words);
    }

    /** A two digit group such as "nineteen", "ninety nine", or "oh five". */
    private static Read pair(List<String> tokens,int i){
        String w=word(tokens,i);
        if(w.equals("oh")&&digitWord(word(tokens,i+1))&&SMALL.get(word(tokens,i+1))>0)return new Read(SMALL.get(word(tokens,i+1)),i+2,2);
        if(SMALL.containsKey(w)&&SMALL.get(w)>=10)return new Read(SMALL.get(w),i+1,1);
        if(TENS.containsKey(w)){
            String next=word(tokens,i+1);
            if(digitWord(next)&&SMALL.get(next)>0)return new Read(TENS.get(w)+SMALL.get(next),i+2,2);
            return new Read(TENS.get(w),i+1,1);
        }
        return null;
    }

    /** Three or more single digits in a row, such as "five five five one two one two". */
    private static int phone(List<String> tokens,int i){
        if(!digitWord(word(tokens,i)))return -1;
        StringBuilder digits=new StringBuilder();int j=i;
        while(j<tokens.size()){
            String w=word(tokens,j);
            if(digitWord(w))digits.append(SMALL.get(w));
            else if(w.equals("oh")&&digits.length()>0&&(digitWord(word(tokens,j+1))||word(tokens,j+1).equals("oh")))digits.append('0');
            else break;
            j++;
        }
        if(digits.length()<3)return -1;
        String d=digits.toString();
        if(d.length()==7)d=d.substring(0,3)+"-"+d.substring(3);
        else if(d.length()==10)d=d.substring(0,3)+"-"+d.substring(3,6)+"-"+d.substring(6);
        return replace(tokens,i,j,d);
    }

    /** "nineteen ninety nine" or "twenty twenty six". The first word must be eighteen, nineteen, or twenty. */
    private static int year(List<String> tokens,int i){
        Read first=pair(tokens,i);
        if(first==null||first.end!=i+1||first.value<18||first.value>20)return -1;
        Read second=pair(tokens,first.end);
        if(second==null||second.value<1)return -1;
        // "twenty twenty" alone is a year only when nothing makes it a quantity.
        if(scaleNext(tokens,second.end))return -1;
        return replace(tokens,i,second.end,String.valueOf(first.value*100+second.value));
    }

    /** "at three thirty" or "seven oh five pm" become "3:30" and "7:05 pm". */
    private static int time(List<String> tokens,int i){
        Integer hour=SMALL.get(word(tokens,i));
        if(hour==null||hour<1||hour>12)return -1;
        Read minutes=pair(tokens,i+1);
        if(minutes==null||minutes.value<1||minutes.value>59)return -1;
        String after=word(tokens,minutes.end);
        boolean clock=BEFORE_TIME.contains(word(tokens,i-1))||after.equals("am")||after.equals("pm")||after.equals("a.m")||after.equals("p.m");
        if(!clock)return -1;
        return replace(tokens,i,minutes.end,hour+":"+(minutes.value<10?"0":"")+minutes.value);
    }

    /** "twenty first" becomes "21st". Ordinals up to "tenth" stay words. */
    private static int ordinal(List<String> tokens,int i){
        String w=word(tokens,i);
        int value,end;
        if(TENS.containsKey(w)&&ORDINAL.containsKey(word(tokens,i+1))&&ORDINAL.get(word(tokens,i+1))<10){value=TENS.get(w)+ORDINAL.get(word(tokens,i+1));end=i+2;}
        else if(ORDINAL.containsKey(w)&&ORDINAL.get(w)>10){value=ORDINAL.get(w);end=i+1;}
        else return -1;
        int ones=value%10;
        String suffix=value%100>=11&&value%100<=13?"th":ones==1?"st":ones==2?"nd":ones==3?"rd":"th";
        return replace(tokens,i,end,value+suffix);
    }

    private static int quantity(List<String> tokens,int i){
        Read number=cardinal(tokens,i);
        if(number==null)return -1;
        // "a million" and "a billion" read better as words.
        if(number.words==2&&word(tokens,i).equals("a")&&number.value>=1_000_000)return -1;
        int end=number.end;
        String text=format(number.value);
        // Decimals: "three point five".
        if(word(tokens,end).equals("point")&&(digitWord(word(tokens,end+1))||word(tokens,end+1).equals("oh"))){
            StringBuilder decimals=new StringBuilder();int j=end+1;
            while(digitWord(word(tokens,j))||word(tokens,j).equals("oh")){decimals.append(word(tokens,j).equals("oh")?0:SMALL.get(word(tokens,j)));j++;}
            text=number.value+"."+decimals;end=j;
        }
        String unit=word(tokens,end);
        boolean small=number.words==1&&number.value<10&&!text.contains(".");
        if(small){
            if(!UNITS.contains(unit))return -1;
            if(number.value==1&&TIME_UNITS.contains(unit))return -1;
        }
        // Heights: "six foot two" becomes 6'2".
        if((unit.equals("foot")||unit.equals("feet"))&&number.value<10){
            Integer inches=SMALL.get(word(tokens,end+1));
            if(inches!=null&&inches<12){
                int stop=end+2;
                if(word(tokens,stop).equals("inches")||word(tokens,stop).equals("inch"))stop++;
                return replace(tokens,i,stop,number.value+"'"+inches+"\"");
            }
        }
        if((SCALE.containsKey(unit)&&SCALE.get(unit)>=1_000_000)&&text.contains(".")){
            text=text+" "+unit;end++;unit=word(tokens,end);
        }
        if(unit.equals("percent")){text=text+"%";end++;}
        else if(unit.equals("dollars")||unit.equals("dollar")){
            text="$"+text;end++;
            if(word(tokens,end).equals("and")){
                Read cents=cardinal(tokens,end+1);
                if(cents!=null&&cents.value<100&&(word(tokens,cents.end).equals("cents")||word(tokens,cents.end).equals("cent"))){
                    text=text+"."+(cents.value<10?"0":"")+cents.value;end=cents.end+1;
                }
            }
        }
        return replace(tokens,i,end,text);
    }

    /** Writes a number with commas, except years such as 2026, and "5 million" for round millions. */
    private static String format(long value){
        if(value>=1_000_000_000L&&value%1_000_000_000L==0)return value/1_000_000_000L+" billion";
        if(value>=1_000_000L&&value%1_000_000L==0)return value/1_000_000L+" million";
        if(value>=2000&&value<2100&&value%100!=0)return String.valueOf(value);
        return String.format(Locale.ROOT,"%,d",value);
    }

    /** Replaces tokens from start up to end with one token, and returns the index after it. */
    private static int replace(List<String> tokens,int start,int end,String text){
        for(int k=start;k<end;k++)tokens.remove(start);
        tokens.add(start,text);
        return start+1;
    }
}
