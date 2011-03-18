package com.google.code.plsqlgateway.config;

import javax.servlet.ServletContext;

import com.google.code.eforceconfig.Config;
import com.google.code.eforceconfig.EntityConfig;
import com.google.code.eforceconfig.initializers.ClassPathConfigInitializer;
import com.google.code.eforceconfig.sources.managers.ClassPathSourceManager;

public class Configuration
{
	public static String CONTEXT_ATTRIBUTE_NAME= "configuration";
	
	private Config internal;
	private Config webapp; 
	
    private Configuration(ServletContext ctx) 
    {
	    	try
	    	{
		        ClassPathConfigInitializer cci= new ClassPathConfigInitializer();
		        cci.setConfigSourceManager(new ClassPathSourceManager(this.getClass().getClassLoader(),"com.google.code.plsqlgateway.config"));
		        internal= new Config();
		        internal.init(cci);
		        
		        webapp= Config.getConfigSet(ctx.getInitParameter("com.google.code.eforceconfig.CONFIGSET_NAME"));
	    	}
	    	catch (Exception e)
	    	{
	    		throw new RuntimeException(e);
	    	}
    }

    protected void finalize() 
    throws Throwable 
    {
	    	internal.stop();
    }
    
    public EntityConfig getInternal()
    {
	    	return internal.getEntity("internal");
    }
    
    public EntityConfig getDADConfig(String dadName)
    {
	    	return webapp.getEntity("plsqlgateway."+dadName);
    }
    
    public EntityConfig getGeneral()
    {
	    	return webapp.getEntity("plsqlgateway.general");
    }
    
    public synchronized static Configuration getInstance(ServletContext ctx)
    {
	    	Configuration c= null;
	    	
	    	if ((c=(Configuration) ctx.getAttribute(CONTEXT_ATTRIBUTE_NAME))==null)
	    	{
	    		c= new Configuration(ctx);
	    		ctx.setAttribute(CONTEXT_ATTRIBUTE_NAME, c);
	    	}
    		
        return c;
    }
}
