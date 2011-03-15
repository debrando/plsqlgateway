package com.google.code.plsqlgateway.servlet;

import java.lang.reflect.Constructor;
import java.util.ArrayList;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.sql.DataSource;

import org.apache.log4j.Logger;

import oracle.jdbc.pool.OracleDataSource;

import com.google.code.eforceconfig.EntityConfig;
import com.google.code.plsqlgateway.config.Configuration;

public class DADContextListener implements ServletContextListener
{
    public static final String DAD_DATA_SOURCE= "dad-data-source";
	private static final Logger logger= Logger.getLogger(DADContextListener.class);
    
	@SuppressWarnings("unchecked")
	public void contextDestroyed(ServletContextEvent event)
	{
		ServletContext ctx= event.getServletContext();
		Configuration config= Configuration.getInstance(ctx);
		EntityConfig general= config.getGeneral();

		if (general.getBooleanParameter("multiple-dad"))
		{
			ArrayList<String> dads= general.getListParameter("dads");
            
			for (String dadName: dads)
				destroyDAD(dadName, ctx);
		}
		else
		if (general.getBooleanParameter("embedded"))
			destroyDAD("embedded", ctx);
		else
			destroyDAD(ctx.getServletContextName(), ctx);
	}
	
	@SuppressWarnings("unchecked")
	public void contextInitialized(ServletContextEvent event)
	{
		ServletContext ctx= event.getServletContext();
		Configuration config= Configuration.getInstance(ctx);
		
	    try 
	    {

			EntityConfig general= config.getGeneral();
			
			if (general.getBooleanParameter("multiple-dad"))
			{
				ArrayList<String> dads= general.getListParameter("dads");
                
				for (String dadName: dads)
					initializeDAD(dadName, config, ctx);
			}
			else
			if (general.getBooleanParameter("embedded"))
			    initializeDAD("embedded", config, ctx);
			else
				initializeDAD(ctx.getServletContextName(), config, ctx);
		}
	    catch (Exception e)
	    {
			throw new RuntimeException(e);
		}
	    
	}

	@SuppressWarnings("unchecked")
	private static void initializeDAD(String dadName, Configuration config, ServletContext ctx)
	throws Exception
	{
		logger.info("init DAD: "+dadName);
        
		EntityConfig dadConfig= config.getDADConfig(dadName);
		String dewrapperClassName= dadConfig.getParameter("jndi-connection-dewrapper");
		String jndiDataSourceName= dadConfig.getParameter("jndi-datasource");
		DataSource ds= null;
		
		if (dewrapperClassName!=null)
		{
		    Constructor<DataSource> c= (Constructor<DataSource>) Thread.currentThread().getContextClassLoader().loadClass(dewrapperClassName).getConstructor(DataSource.class);
		    ds= c.newInstance(lookupDataSource(jndiDataSourceName));
		}
		else
		if (jndiDataSourceName!=null)
		    ds= lookupDataSource(jndiDataSourceName);
		else
		{
			ds= new OracleDataSource();
	        configureDS(dadConfig,(OracleDataSource)ds);
		}
    
        ctx.setAttribute(DAD_DATA_SOURCE+"|"+dadName, ds);
	}
	
	private static DataSource lookupDataSource(String jndiName)
	throws NamingException 
	{
		InitialContext ic= new InitialContext();
		DataSource ds= (DataSource) ic.lookup(jndiName);
		ic.close();
		return ds;
	}

	private static void destroyDAD(String dadName, ServletContext ctx)
	{
		logger.info("destroy DAD: "+dadName);
        
		ctx.removeAttribute(DAD_DATA_SOURCE+"|"+dadName);
	}

	private static void configureDS(EntityConfig dbconfig, OracleDataSource ds)
	throws Exception
	{
		  ds.setUser(dbconfig.getParameter("user"));
		  ds.setPassword(dbconfig.getParameter("password"));
		  
		  if (dbconfig.getParameter("url")==null)
		  {
			  ds.setDriverType("thin");
			  ds.setPortNumber(dbconfig.getIntParameter("port"));
			  ds.setServerName(dbconfig.getParameter("host"));
			  ds.setDatabaseName(dbconfig.getParameter("sid"));
		  }
		  else
			  ds.setURL(dbconfig.getParameter("url"));
		  
		  ds.setImplicitCachingEnabled(dbconfig.getBooleanParameter("implicit-caching"));
		  ds.setExplicitCachingEnabled(dbconfig.getBooleanParameter("explicit-caching"));
		  ds.setConnectionCachingEnabled(false);
		  ds.setConnectionProperties(dbconfig.getPropertiesParameter("connection-properties"));
	}	
	
}
