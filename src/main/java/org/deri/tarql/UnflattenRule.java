package org.deri.tarql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by lolive on 13/12/18.
 */
public class UnflattenRule {
    private final Pattern p;
    public String matches;
    public String varName;

    public UnflattenRule(String varName, String matches){
        this.varName = varName;
        this.matches = matches;
        this.p = Pattern.compile(matches);
    }

    public String getMatches() {
        return matches;
    }

    public void setMatches(String matches) {
        this.matches = matches;
    }

    public String getVarName() {
        return varName;
    }

    public void setVarName(String varName) {
        this.varName = varName;
    }

    public List<String> apply(String s) {
        List<String> matchedSubstrings = new ArrayList<>();
        Matcher matcher = p.matcher(s);
        while(matcher.find()){
            String g = matcher.group();
            matchedSubstrings.add(g);
        }
        return matchedSubstrings;
    }
}
