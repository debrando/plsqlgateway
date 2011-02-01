package com.google.code.plsqlgateway.servlet;

public class SQLInjectionException extends Exception {

	public SQLInjectionException(String identifier)
	{
	    super("Invalid identifier: "+identifier);	
	}

}
