/*
 * Copyright (C) 2005-2011 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.repo.model.filefolder;

import java.util.List;

import org.alfresco.repo.security.permissions.impl.acegi.ResultsPermissionChecked;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.model.PagingFileInfoResults;
import org.alfresco.util.Pair;

/**
 * Page of FileInfo results
 * 
 * @author janv
 * @since 4.0
 */
/* package */ class PagingFileInfoResultsImpl implements PagingFileInfoResults, ResultsPermissionChecked
{
    private List<FileInfo> nodeInfos;
    
    private Boolean hasMoreItems;
    private Pair<Integer, Integer> totalResultCount;
    private String queryExecutionId;
    private boolean permissionChecked;
    
    public PagingFileInfoResultsImpl(List<FileInfo> nodeInfos, Boolean hasMoreItems, Pair<Integer, Integer> totalResultCount, String queryExecutionId, boolean permissionChecked)
    {
        this.nodeInfos = nodeInfos;
        this.hasMoreItems = hasMoreItems;
        this.totalResultCount = totalResultCount;
        this.queryExecutionId = queryExecutionId;
        this.permissionChecked = permissionChecked;
    }
    
    public List<FileInfo> getPage()
    {
        return nodeInfos;
    }
    
    public Boolean hasMoreItems()
    {
        return hasMoreItems;
    }
    
    public Pair<Integer, Integer> getTotalResultCount()
    {
        return totalResultCount;
    }
    
    public String getQueryExecutionId()
    {
        return queryExecutionId;
    }
    
    public boolean permissionsChecked()
    {
        return permissionChecked;
    }
}
