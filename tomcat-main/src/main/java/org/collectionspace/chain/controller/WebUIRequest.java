/* Copyright 2010 University of Cambridge
 * Licensed under the Educational Community License (ECL), Version 2.0. You may not use this file except in 
 * compliance with this License.
 *
 * You may obtain a copy of the ECL 2.0 License at https://source.collectionspace.org/collection-space/LICENSE.txt
 */
package org.collectionspace.chain.controller;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.collectionspace.csp.api.persistence.Storage;
import org.collectionspace.csp.api.ui.Operation;
import org.collectionspace.csp.api.ui.TTYOutputter;
import org.collectionspace.csp.api.ui.UIException;
import org.collectionspace.csp.api.ui.UIRequest;
import org.collectionspace.csp.api.ui.UISession;
import org.collectionspace.csp.api.ui.UIUmbrella;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebUIRequest implements UIRequest {
	private static final Logger log = LoggerFactory.getLogger(WebUIRequest.class);
	private static final String COOKIENAME="CSPACESESSID";
	private Integer lifeInMins = 45;

	private HttpServletRequest request;
	private HttpServletResponse response;
	private String[] ppath,rpath=null;
	private boolean failure=false,secondary_redirect=false;
	private Exception failing_exception;
	private Operation operation_performed=Operation.READ;
	private Map<String,String> rargs=new HashMap<String,String>();
	private PrintWriter out=null;
	private String out_data=null; // We store to allow late changes to headers
	private String body; // XXX what if it's binary?
	private WebUIUmbrella umbrella;
	private WebUISession session;
	private boolean solidified=false;

	private void initRequest(UIUmbrella umbrella,HttpServletRequest request,HttpServletResponse response) throws IOException, UIException{
		this.request=request;
		this.response=response; 
		List<String> p=new ArrayList<String>();
		for(String part : request.getPathInfo().split("/")) {
			if("".equals(part))
				continue;
			p.add(part);
		}		
		this.ppath=p.toArray(new String[0]);
		body=IOUtils.toString(request.getInputStream(),"UTF-8");
		if(!(umbrella instanceof WebUIUmbrella))
			throw new UIException("Bad umbrella");
		this.umbrella=(WebUIUmbrella)umbrella;
		session=calculateSessionId();
		
	}
	public WebUIRequest(UIUmbrella umbrella,HttpServletRequest request,HttpServletResponse response, Integer cookieLife) throws IOException, UIException {
		this.lifeInMins = cookieLife;
		initRequest(umbrella,request,response);
	}

	public WebUIRequest(UIUmbrella umbrella,HttpServletRequest request,HttpServletResponse response) throws IOException, UIException {
		this.lifeInMins = 15;
		initRequest(umbrella,request,response);
	}

	private WebUISession calculateSessionId() throws UIException {
		
		Cookie[] cookies=request.getCookies();
		if(cookies==null)
			cookies=new Cookie[0];
		for(Cookie cookie : cookies) {
			if(!COOKIENAME.equals(cookie.getName()))
				continue;
			WebUISession session=umbrella.getSession(cookie.getValue());
			if(session!=null)
				return session;
		}
		// No valid session: make our own
		return umbrella.createSession();
	}

	// XXX expire sessions
	private void setSession() {
		//if(session.isOld())
		//	return; // No need to reset session

		Cookie cookie=new Cookie(COOKIENAME,session.getID());
		cookie.setPath("/");//XXX should be /chain - so either need to have a parameter in cspace-config or try and ask tomcat who we are
		cookie.setMaxAge(60 * lifeInMins);
		response.addCookie(cookie);
	}

	// NOTE No changes to solidified stuff can happen after you get the TTY outputter
	public TTYOutputter getTTYOutputter() throws UIException { 
		try {
			WebTTYOutputter tty=new WebTTYOutputter(response);
			solidify(false);
			out=tty.getWriter();
			return tty;
		} catch (IOException e) {
			throw new UIException("Cannot create response PrintWriter",e);
		}
	}

	public String[] getPrincipalPath() throws UIException { return ppath; }

	public void setFailure(boolean isit, Exception why) throws UIException {
		failure=isit;
		failing_exception=why;
	}

	public void setOperationPerformed(Operation op) throws UIException {
		operation_performed=op;
	}

	public void setRedirectPath(String[] in) throws UIException {
		rpath=in;
		secondary_redirect=false;
	}

	public void setSecondaryRedirectPath(String[] in) throws UIException {
		rpath=in;
		secondary_redirect=true;
	}

	public void deleteRedirectArgument(String key) throws UIException {
		rargs.remove(key);
	}

	public String getRedirectArgument(String key) throws UIException {
		return rargs.get(key);
	}

	public void setRedirectArgument(String key, String value) throws UIException {
		rargs.put(key,value);
	}

	public String getRequestArgument(String key) throws UIException {
		String param =  request.getParameter(key);
		if(param != null)
			try {
				param = new String(param.getBytes("8859_1"),"UTF8");
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				param = request.getParameter(key);
			}
		
		return param;
	}
	public Set<String> getAllRequestArgument() throws UIException {
		Set<String> params = new HashSet<String>();
		Enumeration e = request.getParameterNames();
		while (e.hasMoreElements()) {
			String name = (String)e.nextElement();
			params.add(name);
		}
		return params;
	}

	public Operation getRequestedOperation() throws UIException {
		String method=request.getMethod();
		if("POST".equals(method))
			return Operation.CREATE;
		else if("PUT".equals(method))
			return Operation.UPDATE;
		else if("DELETE".equals(method))
			return Operation.DELETE;
		else if("GET".equals(method))
			return Operation.READ;
		return Operation.READ;
	}

	private void set_status() {
		switch(operation_performed) {
		case CREATE:
			response.setStatus(201);
			break;
		default:
			response.setStatus(200);
		}
	}

	private String aWhileAgoAsExpectedByExpiresHeader() {
		SimpleDateFormat format=new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss zzz");
		Date a_while_ago=new Date(new Date().getTime()-24*60*60*1000);
		return format.format(a_while_ago);
	}

	private void close() {
		if(out!=null)
			out.close();
		out=null;
	}
	
	public void solidify(boolean close) throws UIException {
		try {
			if(out_data!=null) {
				out=response.getWriter();
				out.print(out_data);
				out_data=null;
			}
			if(solidified) {
				if(close)
					close();
				return;
			}
			solidified=true;
			/* We always disable cacheing for now (for IE). We probably want to be cleverer at some point. XXX */
			response.addHeader("Pragma","no-cache");
			response.addHeader("Last-Modified",aWhileAgoAsExpectedByExpiresHeader());
			response.addHeader("Cache-Control","no-store, no-cache, must-revalidate");
			response.addHeader("Cache-Control","post-check=0, pre-check=0");
			/* End of cacheing stuff */
			if(failure) {
				// Failed
				response.setStatus(400);
				if(failing_exception!=null)
					response.sendError(400,ExceptionUtils.getFullStackTrace(failing_exception));
				else
					response.sendError(400,"No underlying exception");
			} else {
				// Success
				setSession();
				if(rpath!=null) {
					// Redirect
					StringBuffer path=new StringBuffer();
					for(String part : rpath) {
						if("".equals(part))
							continue;
						path.append('/');
						path.append(part);
					}
					boolean first=true;
					for(Map.Entry<String,String> e : rargs.entrySet()) {
						path.append(first?'?':'&');
						first=false;
						path.append(URLEncoder.encode(e.getKey(),"UTF-8"));
						path.append('=');
						path.append(URLEncoder.encode(e.getValue(),"UTF-8"));
					}
					if(secondary_redirect)
						set_status();
					else
						response.setStatus(303);
					response.setHeader("Location",path.toString());
				} else {
					set_status();
				}
			}
			if(close)
				close();
		} catch (IOException e) {
			throw new UIException("Could not send error",e);
		}
	}

	public void sendXMLResponse(String data) throws UIException {
		response.setContentType("text/xml;charset=UTF-8");
		out_data=data;
	}
	public void sendJSONResponse(JSONObject data) throws UIException {
		response.setContentType("text/json;charset=UTF-8");
		out_data=data.toString();
	}
	public void sendJSONResponse(JSONArray data) throws UIException {
		response.setContentType("text/json;charset=UTF-8");
		out_data=data.toString();
	}

	public JSONObject getPostBody() throws UIException {
		JSONObject jsondata = new JSONObject();
		String jsonString = body;
		try {
			if(jsonString.length()>0){
				String[] data = jsonString.split("&");
				for(String item : data){
					String[] itembits = item.split("=");
					jsondata.put(URLDecoder.decode(itembits[0],"UTF-8"), URLDecoder.decode(itembits[1],"UTF-8"));
				}
			}

		} catch (JSONException e) {
			throw new UIException("Cannot get request body, JSONException",e);
		} catch (UnsupportedEncodingException e) {
			throw new UIException("Cannot get request body, UnsupportedEncodingException",e);
		}
		return jsondata;
	}

	public Boolean isJSON() throws UIException {
		try{
			new JSONObject(body);
			return true;
		}
		catch (JSONException e){
			return false;
		}
	}

	public JSONObject getJSONBody() throws UIException {
		try {
			String jsonString = body;
			if (StringUtils.isBlank(jsonString)) {
				throw new UIException("No JSON content to store");
			}

			// Store it
			return new JSONObject(jsonString);
		} catch (JSONException e) {
			throw new UIException("Cannot get request body, JSONException",e);
		}
	}

	public UISession getSession() throws UIException { return session; }
}
