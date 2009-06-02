package org.collectionspace.chain.controller;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.collectionspace.chain.jsonstore.ExistException;
import org.collectionspace.chain.jsonstore.JSONNotFoundException;
import org.collectionspace.chain.jsonstore.JSONStore;
import org.collectionspace.chain.jsonstore.StubJSONStore;
import org.collectionspace.chain.schema.SchemaStore;
import org.collectionspace.chain.schema.StubSchemaStore;
import org.collectionspace.chain.services.ReturnedDocument;
import org.collectionspace.chain.services.ServicesConnection;
import org.collectionspace.chain.test.TestServlet;
import org.collectionspace.chain.util.RequestMethod;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mortbay.jetty.testing.HttpTester;
import org.mortbay.jetty.testing.ServletTester;

public class HandleJSONTest {
	
	private final static String testStr = "{\"items\":[{\"value\":\"This is an experimental widget being tested. It will not do what you expect.\"," +
	                        "\"title\":\"\",\"type\":\"caption\"},{\"title\":\"Your file\",\"type\":\"resource\",\"param\":\"file\"}," +
	                        "{\"title\":\"Author\",\"type\":\"text\",\"param\":\"author\"},{\"title\":\"Title\",\"type\":\"text\"," +
	                        "\"param\":\"title\"},{\"title\":\"Type\",\"type\":\"dropdown\",\"values\":[{\"value\":\"1\",\"text\":" +
	                        "\"thesis\"},{\"value\":\"2\",\"text\":\"paper\"},{\"value\":\"3\",\"text\":\"excel-controlled\"}]," +
	                        "\"param\":\"type\"}]}";
	
	private final static String testStr2 = "{\"a\":\"b\"}";
	
	
	private JSONStore store;
	
	@Before public void setup() {
		File tmp=new File(System.getProperty("java.io.tmpdir"));
		File dir=new File(tmp,"ju-cspace");
		if(!dir.exists())
			dir.mkdir();
		// XXX do it properly when we have delete
		for(File f : dir.listFiles()) {
			f.delete();
		}
		store=new StubJSONStore(dir.toString());
	}

	
	@Test public void writeJSONToFile() throws JSONException, ExistException {
		JSONObject jsonObject = new JSONObject(testStr);
		store.createJSON("/objects/json1.test", jsonObject);
	}
	
	@Test public void readJSONFromFile() throws JSONNotFoundException, JSONException, ExistException {
		JSONObject jsonObject = new JSONObject(testStr);
		store.createJSON("/objects/json1.test", jsonObject);
		String result = store.retrieveJSON("/objects/json1.test");
		JSONObject resultObj = new JSONObject(result);
		JSONObject testObj = new JSONObject(testStr);
		assertTrue(resultObj.toString().equals(testObj.toString()));
	}

	@Test public void testJSONNotExist() throws JSONException {
		try
		{
			String result = store.retrieveJSON("nonesuch.json");
			new JSONObject(result);
			assertTrue(false);
		}
		catch (JSONNotFoundException onfe) {}
	}
	
	@Test public void testJSONUpdate() throws ExistException, JSONNotFoundException, JSONException {
		JSONObject jsonObject = new JSONObject(testStr2);
		store.createJSON("/objects/json1.test", jsonObject);
		jsonObject = new JSONObject(testStr);
		store.updateJSON("/objects/json1.test", jsonObject);		
		String result = store.retrieveJSON("/objects/json1.test");
		JSONObject resultObj = new JSONObject(result);
		JSONObject testObj = new JSONObject(testStr);
		assertTrue(resultObj.toString().equals(testObj.toString()));
	}

	@Test public void testJSONNoUpdateNonExisting() throws ExistException, JSONNotFoundException, JSONException {
		JSONObject jsonObject = new JSONObject(testStr);
		try {
			store.updateJSON("/objects/json1.test", jsonObject);
			assertTrue(false);
		} catch(ExistException e) {}
	}

	@Test public void testJSONNoCreateExisting() throws ExistException, JSONNotFoundException, JSONException {
		JSONObject jsonObject = new JSONObject(testStr);
		store.createJSON("/objects/json1.test", jsonObject);
		try {
			store.createJSON("/objects/json1.test", jsonObject);
		} catch(ExistException e) {}			
	}
	
	private File tmpSchemaFile() {
		String tmpdir=System.getProperty("java.io.tmpdir");
		return new File(tmpdir,"test-json-handle.tmp");
	}
	
	private void createSchemaFile() throws IOException {
		File file=tmpSchemaFile();
		FileOutputStream out=new FileOutputStream(file);
		IOUtils.write(testStr2,out);
		out.close();
	}
	
	private void deleteSchemaFile() {
		File file=tmpSchemaFile();
		file.delete();
	}
	
	@Test public void testSchemaStore() throws IOException, JSONException {
		String tmpdir=System.getProperty("java.io.tmpdir");
		SchemaStore schema=new StubSchemaStore(tmpdir);
		createSchemaFile();
		JSONObject j=schema.getSchema("test-json-handle.tmp");
		assertEquals(testStr2,j.toString());
		deleteSchemaFile();
	}
	
	private ServletTester setupJetty() throws Exception {
		ServletTester tester=new ServletTester();
		tester.setContextPath("/chain");
		tester.addServlet(ChainServlet.class, "/*");
		tester.addServlet("org.mortbay.jetty.servlet.DefaultServlet", "/");
		tester.start();
		return tester;
	}
	
	private String setupTestServer() throws Exception {
		ServletTester tester=new ServletTester();
		tester.setContextPath("/test");
		tester.addServlet(TestServlet.class,"/*");
		tester.addServlet("org.mortbay.jetty.servlet.DefaultServlet", "/");
		String connector=tester.createSocketConnector(true);
		System.err.println("connector="+connector);
		tester.start();
		return connector;
	}
	
	private HttpTester jettyDo(ServletTester tester,String method,String path,String data) throws IOException, Exception {
		HttpTester request = new HttpTester();
		HttpTester response = new HttpTester();
		request.setMethod(method);
		request.setHeader("Host","tester");
		request.setURI(path);
		request.setVersion("HTTP/1.0");		
		if(data!=null)
			request.setContent(data);
		response.parse(tester.getResponses(request.generate()));
		return response;
	}
	
	@Test public void testJettyStartupWorks() throws Exception {
		setupJetty();
	}
	
	@Test public void testSchemaGet() throws Exception {
		createSchemaFile();
		HttpTester out=jettyDo(setupJetty(),"GET","/chain/objects/schema/test-json-handle.tmp",null);
		assertEquals(out.getMethod(),null);
		assertEquals(200,out.getStatus());
		deleteSchemaFile();
		assertEquals(testStr2,out.getContent());
	}
	
	@Test public void testSchemaPost() throws Exception {
		deleteSchemaFile();
		ServletTester jetty=setupJetty();
		HttpTester out=jettyDo(jetty,"POST","/chain/objects/test-json-handle.tmp",testStr2);	
		assertEquals(out.getMethod(),null);
		System.err.println(out.getContent());
		assertEquals(201,out.getStatus());
		out=jettyDo(jetty,"GET","/chain/objects/test-json-handle.tmp",null);
		assertEquals(testStr2,out.getContent());
		out=jettyDo(jetty,"PUT","/chain/objects/test-json-handle.tmp",testStr);
		assertEquals(200,out.getStatus());		
		out=jettyDo(jetty,"GET","/chain/objects/test-json-handle.tmp",null);
		assertEquals(testStr,out.getContent());		
	}
	
	@Test public void testServeStatic() throws Exception {
		HttpTester out=jettyDo(setupJetty(),"GET","/chain/TestForm.html",null);
		assertEquals(200,out.getStatus());
		assertTrue(out.getContent().contains("<html>"));
	}
	
	@Test public void testObjectList() throws Exception {
		ServletTester jetty=setupJetty();
		jettyDo(jetty,"POST","/chain/objects/a",testStr2);	
		jettyDo(jetty,"POST","/chain/objects/b",testStr2);	
		jettyDo(jetty,"POST","/chain/objects/c",testStr2);	
		HttpTester out=jettyDo(jetty,"GET","/chain/objects",null);
		assertEquals(200,out.getStatus());
		JSONObject result=new JSONObject(out.getContent());
		JSONArray items=result.getJSONArray("items");
		Set<String> files=new HashSet<String>();
		for(int i=0;i<items.length();i++)
			files.add(items.getString(i));
		assertTrue(files.contains("a"));
		assertTrue(files.contains("b"));
		assertTrue(files.contains("c"));
		assertEquals(3,files.size());
	}
	
	@Test public void testXMLDocumentRetrieve() throws Exception {
		String base=setupTestServer();
		ServicesConnection conn=new ServicesConnection(base+"/test");
		ReturnedDocument retdoc=conn.getXMLDocument(RequestMethod.GET,"test.xml");
		assertEquals(200,retdoc.getStatus());
		Document doc=retdoc.getDocument();
		assertEquals(1,doc.selectNodes("test").size());
	}
	
	private Document makeXML(String in) throws DocumentException, UnsupportedEncodingException {
		SAXReader reader=new SAXReader();
		return reader.read(new ByteArrayInputStream(in.getBytes("UTF-8")));
	}
	
	@Test public void testReflectTest() throws Exception {
		String base=setupTestServer();
		ServicesConnection conn=new ServicesConnection(base+"/test/");
		ReturnedDocument retdoc=conn.getXMLDocument(RequestMethod.POST,"/reflect",makeXML("<hello/>"));
		assertEquals(200,retdoc.getStatus());
		Document doc=retdoc.getDocument();
		assertEquals(1,doc.selectNodes("hello").size());		
	}
}
