package com.alucn.casemanager.client.thread;

import java.io.IOException;

import net.sf.json.JSONObject;

import com.alucn.casemanager.client.constants.Constants;

public class Test {
	public static void main(String[] args) throws IOException {
		JSONObject test = Constants.CASESTATUSPRE.getJSONObject("body");
		JSONObject test1 = test.getJSONObject(Constants.TASKSTATUS);
		test1.put(Constants.STATUS, "dead");
		System.out.println(test.toString());
	}
}
