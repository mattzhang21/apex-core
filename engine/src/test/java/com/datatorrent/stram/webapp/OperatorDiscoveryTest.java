/**
 * Copyright (c) 2012-2015 DataTorrent, Inc.
 * All rights reserved.
 */
package com.datatorrent.stram.webapp;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.SynchronousQueue;

import org.apache.hadoop.conf.Configuration;
import org.apache.tools.ant.DirectoryScanner;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.common.util.BaseOperator;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.logical.LogicalPlan.OperatorMeta;
import com.datatorrent.stram.plan.logical.LogicalPlanConfiguration;
import com.datatorrent.stram.util.ObjectMapperFactory;
import com.datatorrent.stram.webapp.TypeDiscoverer.UI_TYPE;
import com.google.common.collect.Lists;

public class OperatorDiscoveryTest
{
  private static final Logger LOG = LoggerFactory.getLogger(OperatorDiscoveryTest.class);
  
  @Test
  public void testPropertyDiscovery() throws Exception
  {
    
    String[] classFilePath = getClassFileInClasspath();
    OperatorDiscoverer od = new OperatorDiscoverer(classFilePath);
    od.buildTypeGraph();

    Assert.assertNotNull(od.getOperatorClass(BaseOperator.class.getName()));

    JSONObject desc = od.describeClass(TestOperator.class);
    LOG.debug("\ntype info for " + TestOperator.class + ":\n" + desc.toString(2));

    JSONObject asmDesc = od.describeClassByASM(TestOperator.class.getName());
    LOG.debug("\n(ASM)type info for " + TestOperator.class + ":\n" + asmDesc.toString(2));

    JSONArray props = asmDesc.getJSONArray("properties");
    Assert.assertNotNull("properties", props);
    Assert.assertEquals("properties " + props, 27, props.length());

    JSONObject mapProperty = getJSONProperty(props, "map");
    Assert.assertEquals("canGet " + mapProperty, true, mapProperty.get("canGet"));
    Assert.assertEquals("canSet " + mapProperty, true, mapProperty.get("canSet"));
    Assert.assertEquals("type " + mapProperty, java.util.Map.class.getName(), mapProperty.get("type"));

    JSONArray typeArgs = mapProperty.getJSONArray("typeArgs");
    Assert.assertNotNull("typeArgs", typeArgs);
    Assert.assertEquals("typeArgs " + typeArgs, 2, typeArgs.length());
    Assert.assertEquals("", String.class.getName(), typeArgs.getJSONObject(0).get("type"));
    Assert.assertEquals("", Structured.class.getName(), typeArgs.getJSONObject(1).get("type"));

    JSONObject enumDesc = od.describeClass(Color.class);

    JSONArray enumNames = enumDesc.getJSONArray("enum");
    Assert.assertNotNull("enumNames", enumNames);
    Assert.assertEquals("", Color.BLUE.name(), enumNames.get(0));
    JSONArray enumProps = enumDesc.getJSONArray("properties");
    Assert.assertNotNull("properties", enumProps);
    Assert.assertEquals("props " + enumProps, 0, enumProps.length());

    JSONObject structuredProperty = getJSONProperty(props, "nested");
    Assert.assertEquals("type " + structuredProperty, Structured.class.getName(), structuredProperty.get("type"));

    JSONObject genericArray = getJSONProperty(props, "genericArray");
    Assert.assertEquals("type " + genericArray, Object[].class.getName(), genericArray.get("type"));

    JSONObject propProperty = getJSONProperty(props, "props");
    Assert.assertEquals("uitype " + propProperty, UI_TYPE.MAP.getName(), propProperty.get("uiType"));

    JSONObject stringArrayProperty = getJSONProperty(props, "stringArray");
    Assert.assertEquals("type " + stringArrayProperty, String[].class.getName(), stringArrayProperty.get("type"));

    JSONObject nestedParameterizedTypeProperpty = getJSONProperty(props, "nestedParameterizedType");
    Assert.assertEquals("type " + nestedParameterizedTypeProperpty, Map.class.getName(), nestedParameterizedTypeProperpty.get("type"));
    Assert.assertEquals("type " + nestedParameterizedTypeProperpty, Number.class.getName(),
        nestedParameterizedTypeProperpty.getJSONArray("typeArgs").getJSONObject(1).getJSONArray("typeArgs").getJSONObject(0).getJSONArray("typeArgs").getJSONObject(1).get("type"));

    JSONObject wildcardType = getJSONProperty(props, "wildcardType");
    Assert.assertEquals("type " + wildcardType, Map.class.getName(), wildcardType.get("type"));
    Assert.assertEquals("type " + wildcardType, "class " + Long.class.getName(),
        wildcardType.getJSONArray("typeArgs").getJSONObject(1).getJSONObject("typeBounds").getJSONArray("lower").get(0));

    JSONObject multiDimensionPrimitiveArray = getJSONProperty(props, "multiDimensionPrimitiveArray");
    Assert.assertEquals("type " + multiDimensionPrimitiveArray, int[][].class.getName(), multiDimensionPrimitiveArray.get("type"));

    desc = od.describeClass(ExtendedOperator.class);
    props = desc.getJSONArray("properties");
    genericArray = getJSONProperty(props, "genericArray");
    Assert.assertEquals("type " + genericArray, String[].class.getName(), genericArray.get("type"));

    // type is not a primitive type
    // fetch property meta data to find out how to render it
    desc = od.describeClass(Structured.class);
    LOG.debug("\ntype info for " + Structured.class + ":\n" + desc.toString(2));

    desc = od.describeClass(Color.class);
    LOG.debug("\ntype info for " + Color.class + ":\n" + desc.toString(2));

    desc = od.describeClass(Properties.class);
    LOG.debug("\ntype info for " + Properties.class + ":\n" + desc.toString(2));

    desc = od.describeClass(HashMap.class);
    LOG.debug("\ntype info for " + HashMap.class + ":\n" + desc.toString(2));

    LOG.debug("\n(ASM)type info for " + Color.class + ":\n" + od.describeClassByASM(Color.class.getName()).toString(2));

    LOG.debug("\n(ASM)type info for " + Structured.class + ":\n" + od.describeClassByASM(Structured.class.getName()).toString(2));

  }

  private String[] getClassFileInClasspath()
  {
    String classpath = System.getProperty("java.class.path");
    String[] paths = classpath.split(":");
    List<String> fnames = new LinkedList<String>();
    for (String cp : paths) {
      File f = new File(cp);
      if(!f.isDirectory()){
        continue;
      }
      DirectoryScanner ds = new DirectoryScanner();
      ds.setBasedir(f);
      ds.setIncludes(new String[] { "**\\*.class" });
      ds.scan();
      for (String name : ds.getIncludedFiles()) {
        fnames.add(new File(f, name).getAbsolutePath());
      }

    }
    return fnames.toArray(new String[]{});
  }

  private JSONObject getJSONProperty(JSONArray props, String name) throws JSONException
  {
    for (int i = 0; i < props.length(); i++) {
      if(props.getJSONObject(i).get("name").equals(name)){
        return props.getJSONObject(i);
      }
    }
    return null;
  }

  @Test
  public void testFindDescendants() throws Exception
  {
    OperatorDiscoverer od = new OperatorDiscoverer();

    LOG.debug("The descendants list of java type java.util.Map: \n" + od.getDescendants("java.util.Map"));

    LOG.debug("The descendants list of java type java.util.List: \n" + od.getDescendants("java.util.List"));

    LOG.debug("The initializable descendants list of type java.util.Map: \n" + od.getTypeGraph().getInitializableDescendants("java.util.Map"));

    LOG.debug("The initializable descendants list of type java.util.List: \n" + od.getTypeGraph().getInitializableDescendants("java.util.List"));

    LOG.debug("The initializable descendants list of type java.util.HashMap: \n" + od.getTypeGraph().getInitializableDescendants("java.util.HashMap"));


    String[] jdkQueue = new String[] {DelayQueue.class.getName(), LinkedBlockingDeque.class.getName(),
        LinkedBlockingQueue.class.getName(), PriorityBlockingQueue.class.getName(), SynchronousQueue.class.getName()};
    List<String> actualQueueClass = od.getTypeGraph().getInitializableDescendants("java.util.concurrent.BlockingQueue");

//     at lease include all the classes in jdk
    LOG.debug(actualQueueClass.toString());

    for (String expectedClass : jdkQueue) {
      Assert.assertTrue("Actual queue set should contain any one of the expected class ", actualQueueClass.contains(expectedClass));
    }
    LOG.debug("The initializable descendants of type java.util.concurrent.BlockingQueue: \n" + od.getTypeGraph().getInitializableDescendants("java.util.concurrent.BlockingQueue"));

  }

  @Test
  public void testValueSerialization() throws Exception
  {
    TestOperator<String, Map<String, Number>> bean = new TestOperator<String, Map<String, Number>>();
    bean.map.put("key1", new Structured());
    bean.stringArray = new String[] { "one", "two", "three" };
    bean.stringList = Lists.newArrayList("four", "five");
    bean.props = new Properties();
    bean.props.setProperty("key1", "value1");
    bean.structuredArray = new Structured[]{new Structured()};
    bean.genericArray = new String[] {"s1"};
    bean.structuredArray[0].name = "s1";
    bean.color = Color.BLUE;
    bean.booleanProp = true;
    bean.nestedList = new LinkedList<OperatorDiscoveryTest.Structured>();
    Structured st = new Structured();
    st.name = "nestedone";
    st.size = 10;
    bean.nestedList.add(st);
    bean.uri = new URI("file:///tmp/file");
    bean.integerProp = 44;


    ObjectMapper mapper = ObjectMapperFactory.getOperatorValueSerializer();
    String s = mapper.writeValueAsString(bean);
    JSONObject jsonObj = new JSONObject(s);
    Assert.assertTrue("Null property 'nested' should be cut off", !jsonObj.has("nested"));
    LOG.debug(jsonObj.toString(2));


    TestOperator<?, ?> clone = mapper.readValue(s, TestOperator.class);
    Assert.assertNotNull(clone.structuredArray);
    Assert.assertEquals(Color.BLUE, clone.color);
    Assert.assertEquals(bean.structuredArray.length, clone.structuredArray.length);
    Assert.assertEquals(bean.integerProp, clone.integerProp);
    Assert.assertEquals(bean.uri, clone.uri);


  }

  public static class Structured
  {
    private int size;
    private String name;
    private ArrayList<String> list;

    public int getSize()
    {
      return size;
    }

    public void setSize(int size)
    {
      this.size = size;
    }

    public String getName()
    {
      return name;
    }

    public void setName(String name)
    {
      this.name = name;
    }

    public ArrayList<String> getList()
    {
      return list;
    }

    public void setList(ArrayList<String> list)
    {
      this.list = list;
    }

    @Override
    public int hashCode()
    {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((list == null) ? 0 : list.hashCode());
      result = prime * result + ((name == null) ? 0 : name.hashCode());
      result = prime * result + size;
      return result;
    }

    @Override
    public boolean equals(Object obj)
    {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      Structured other = (Structured) obj;
      if (list == null) {
        if (other.list != null)
          return false;
      } else if (!list.equals(other.list))
        return false;
      if (name == null) {
        if (other.name != null)
          return false;
      } else if (!name.equals(other.name))
        return false;
      if (size != other.size)
        return false;
      return true;
    }
    
    
    

  }

  public enum Color
  {
    BLUE,
    RED,
    WHITE
  }

  public static class TestOperator<T, Z extends Map<String, Number>> extends BaseOperator
  {
    private int intProp;
    private long longProp;
    private double doubleProp;
    private boolean booleanProp;
    
    private Integer integerProp;
    private List<String> stringList;
    private List<Structured> nestedList;
    private Properties props;
    private Structured nested;
    private Map<String, Structured> map = new HashMap<String, Structured>();
    private String[] stringArray;
    private Color color;
    private Structured[] structuredArray;
    private T[] genericArray;
    private Map<String, List<Map<String, Number>>> nestedParameterizedType = new HashMap<String, List<Map<String, Number>>>();
    private Map<? extends Object, ? super Long> wildcardType = new HashMap<Object, Number>();
    private List<int[]> listofIntArray = new LinkedList<int[]>();
    private List<T> parameterizedTypeVariable = new LinkedList<T>();
    private Z genericType;
    private int[][] multiDimensionPrimitiveArray;
    private Structured[][] multiDimensionComplexArray;
    private URI uri;
    private String realName = "abc";
    private String getterOnly = "getterOnly";
    
    // this property can not be deserialized by jackson but it will be ignored if it has no setter method
    private Map<Class, String> mProp;
    
    
    public Map<Class, String> getmProp()
    {
      return mProp;
    }
    
    public String getAlias()
    {
      return realName;
    }
    
    public void setAlias(String alias)
    {
      realName = alias;
    }
    
    public String getGetterOnly()
    {
      return getterOnly;
    }
    
    
    public URI getUri()
    {
      return uri;
    }
    
    public void setUri(URI uri)
    {
      this.uri = uri;
    }
    
    
    public void setIntegerProp(Integer integerProp)
    {
      this.integerProp = integerProp;
    }
    
    public Integer getIntegerProp()
    {
      return integerProp;
    }

    public int getIntProp()
    {
      return intProp;
    }

    public void setIntProp(int intProp)
    {
      this.intProp = intProp;
    }

    public long getLongProp()
    {
      return longProp;
    }

    public void setLongProp(long longProp)
    {
      this.longProp = longProp;
    }

    public double getDoubleProp()
    {
      return doubleProp;
    }

    public void setDoubleProp(double doubleProp)
    {
      this.doubleProp = doubleProp;
    }

    public List<String> getStringList()
    {
      return stringList;
    }

    public void setStringList(List<String> stringList)
    {
      this.stringList = stringList;
    }

    public Properties getProps()
    {
      return props;
    }

    public void setProps(Properties props)
    {
      this.props = props;
    }

    public Structured getNested()
    {
      return nested;
    }

    public void setNested(Structured n)
    {
      this.nested = n;
    }

    public Map<String, Structured> getMap()
    {
      return map;
    }

    public void setMap(Map<String, Structured> m)
    {
      this.map = m;
    }

    public Color getColor()
    {
      return color;
    }

    public void setColor(Color color)
    {
      this.color = color;
    }

    public String[] getStringArray()
    {
      return stringArray;
    }
    
    public void setStringArray(String[] stringArray)
    {
      this.stringArray = stringArray;
    }

    public Structured[] getStructuredArray()
    {
      return structuredArray;
    }

    public void setStructuredArray(Structured[] structuredArray)
    {
      this.structuredArray = structuredArray;
    }

    public T[] getGenericArray()
    {
      return genericArray;
    }

    public void setGenericArray(T[] genericArray)
    {
      this.genericArray = genericArray;
    }

    public boolean isBooleanProp() {
      return booleanProp;
    }

    public void setBooleanProp(boolean booleanProp) {
      this.booleanProp = booleanProp;
    }

    public Map<String, List<Map<String, Number>>> getNestedParameterizedType()
    {
      return nestedParameterizedType;
    }

    public void setNestedParameterizedType(Map<String, List<Map<String, Number>>> nestedParameterizedType)
    {
      this.nestedParameterizedType = nestedParameterizedType;
    }


    public Map<? extends Object, ? super Long> getWildcardType()
    {
      return wildcardType;
    }

    public void setWildcardType(Map<? extends Object, ? super Long> wildcardType)
    {
      this.wildcardType = wildcardType;
    }

    public Z getGenericType()
    {
      return genericType;
    }

    public void setGenericType(Z genericType)
    {
      this.genericType = genericType;
    }

    public int[][] getMultiDimensionPrimitiveArray()
    {
      return multiDimensionPrimitiveArray;
    }

    public void setMultiDimensionPrimitiveArray(int[][] multiDimensionPrimitiveArray)
    {
      this.multiDimensionPrimitiveArray = multiDimensionPrimitiveArray;
    }

    public Structured[][] getMultiDimensionComplexArray()
    {
      return multiDimensionComplexArray;
    }

    public void setMultiDimensionComplexArray(Structured[][] multiDimensionComplexArray)
    {
      this.multiDimensionComplexArray = multiDimensionComplexArray;
    }

    public List<int[]> getListofIntArray()
    {
      return listofIntArray;
    }

    public void setListofIntArray(List<int[]> listofIntArray)
    {
      this.listofIntArray = listofIntArray;
    }

    public List<T> getParameterizedTypeVariable()
    {
      return parameterizedTypeVariable;
    }

    public void setParameterizedTypeVariable(List<T> parameterizedTypeVariable)
    {
      this.parameterizedTypeVariable = parameterizedTypeVariable;
    }

    public <AMAZING extends Callable<Map<String, String>>> AMAZING getAmazing(){
      return null;
    }

    public List<Structured> getNestedList()
    {
      return nestedList;
    }

    public void setNestedList(List<Structured> nestedList)
    {
      this.nestedList = nestedList;
    }

  }

  static class ExtendedOperator extends TestOperator<String, Map<String, Number>>
  {
  }

  public static class ArraysHolder
  {
    public int[] intArray =  new int[] { 1, 2, 3 };
    public Structured[] beanArray = new Structured[] {};
    public int[] getIntArray()
    {
      return intArray;
    }
    public void setIntArray(int[] intArray)
    {
      this.intArray = intArray;
    }
    public Structured[] getBeanArray()
    {
      return beanArray;
    }
    public void setBeanArray(Structured[] beanArray)
    {
      this.beanArray = beanArray;
    }
  }

  @Test
  public void testArraySerialization() throws Exception
  {
    OperatorDiscoverer od = new OperatorDiscoverer();
    Assert.assertNotNull(od.getOperatorClass(BaseOperator.class.getName()));
    JSONObject desc = od.describeClass(ArraysHolder.class);
    LOG.debug("\ntype info for " + ArraysHolder.class + ":\n" + desc.toString(2));

    JSONArray props = desc.getJSONArray("properties");
    ArraysHolder ah = new ArraysHolder();

    JSONObject beanArray = getJSONProperty(props, "beanArray");
    Assert.assertEquals("type " + ah.beanArray.getClass(), ah.beanArray.getClass().getName(), beanArray.get("type"));

    JSONObject intArray = getJSONProperty(props, "intArray");
    Assert.assertEquals("type " + ah.intArray.getClass(), ah.intArray.getClass().getName(), intArray.get("type"));

    ObjectMapper mapper = ObjectMapperFactory.getOperatorValueSerializer();
    String s = mapper.writeValueAsString(ah);
    System.out.println(new JSONObject(s).toString(2));

    ArraysHolder clone = mapper.readValue(s, ArraysHolder.class);
    Assert.assertNotNull(clone.intArray);
    Assert.assertArrayEquals(ah.intArray, clone.intArray);

  }
  
  @Test
  public void testLogicalPlanConfiguration() throws Exception
  {
    TestOperator<String, Map<String, Number>> bean = new TestOperator<String, Map<String, Number>>();
    bean.map.put("key1", new Structured());
    bean.stringArray = new String[] { "one", "two", "three" };
    bean.stringList = Lists.newArrayList("four", "five");
    bean.props = new Properties();
    bean.props.setProperty("key1", "value1");
    bean.structuredArray = new Structured[]{new Structured()};
    bean.genericArray = new String[] {"s1"};
    bean.structuredArray[0].name = "s1";
    bean.color = Color.BLUE;
    bean.booleanProp = true;
    bean.realName = "abc";

    ObjectMapper mapper = ObjectMapperFactory.getOperatorValueSerializer();
    String s = mapper.writeValueAsString(bean);
    LOG.debug(new JSONObject(s).toString(2));
    // 
    Assert.assertTrue("Shouldn't contain field 'realName' !", !s.contains("realName"));
    Assert.assertTrue("Should contain property 'alias' !", s.contains("alias"));
    Assert.assertTrue("Shouldn't contain property 'getterOnly' !", !s.contains("getterOnly"));
    JSONObject jsonObj = new JSONObject(s);
    
    // create the json dag representation 
    JSONObject jsonPlan = new JSONObject();
    jsonPlan.put("streams", new JSONArray());
    JSONObject jsonOper = new JSONObject();
    jsonOper.put("name", "Test Operator");
    jsonOper.put("class", TestOperator.class.getName());
    jsonOper.put("properties", jsonObj);
    jsonPlan.put("operators", new JSONArray(Lists.newArrayList(jsonOper)));
    
    
    Configuration conf = new Configuration(false);
    LogicalPlanConfiguration lpc = new LogicalPlanConfiguration(conf);
    // create logical plan from the json 
    LogicalPlan lp = lpc.createFromJson(jsonPlan, "jsontest");
    OperatorMeta om = lp.getOperatorMeta("Test Operator");
    Assert.assertTrue(om.getOperator() instanceof TestOperator);
    @SuppressWarnings("rawtypes")
    TestOperator beanBack = (TestOperator) om.getOperator();
    
    // The operator deserialized back from json should be same as original operator
    Assert.assertEquals(bean.map, beanBack.map);
    Assert.assertArrayEquals(bean.stringArray, beanBack.stringArray);
    Assert.assertEquals(bean.stringList, beanBack.stringList);
    Assert.assertEquals(bean.props, beanBack.props);
    Assert.assertArrayEquals(bean.structuredArray, beanBack.structuredArray);
    Assert.assertArrayEquals(bean.genericArray, beanBack.genericArray);
    Assert.assertEquals(bean.color, beanBack.color);
    Assert.assertEquals(bean.booleanProp, beanBack.booleanProp);
    Assert.assertEquals(bean.realName, beanBack.realName);
    Assert.assertEquals(bean.getterOnly, beanBack.getterOnly);
    
    
  }

}
