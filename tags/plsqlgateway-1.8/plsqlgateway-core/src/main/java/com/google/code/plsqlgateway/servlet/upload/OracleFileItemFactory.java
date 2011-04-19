package com.google.code.plsqlgateway.servlet.upload;

import oracle.jdbc.OracleConnection;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;

import com.google.code.eforceconfig.EntityConfig;

public class OracleFileItemFactory implements FileItemFactory {

    private OracleConnection conn;
    private EntityConfig intconfig;
    private EntityConfig dadConfig;
    
    public OracleFileItemFactory(OracleConnection conn, EntityConfig intconfig, EntityConfig dadConfig) 
    {
    	this.conn= conn;
    	this.intconfig= intconfig;
    	this.dadConfig= dadConfig;
    }

    public FileItem createItem(String fieldName, String contentType, boolean isFormField, String fileName) {
            return new OracleFileItem(conn, intconfig, dadConfig, fieldName, contentType, isFormField, fileName);
    }
   
}
