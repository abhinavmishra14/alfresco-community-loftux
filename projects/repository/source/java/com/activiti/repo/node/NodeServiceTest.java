package com.activiti.repo.node;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;

import com.activiti.repo.domain.Node;
import com.activiti.repo.domain.hibernate.NodeImpl;
import com.activiti.repo.ref.ChildAssocRef;
import com.activiti.repo.ref.NodeRef;
import com.activiti.repo.ref.Path;
import com.activiti.repo.ref.StoreRef;
import com.activiti.repo.store.StoreService;
import com.activiti.util.BaseSpringTest;

/**
 * Tests the default implementation of the {@link com.activiti.repo.service.NodeService}
 * 
 * @author Derek Hulley
 */
public class NodeServiceTest extends BaseSpringTest
{
    private StoreService storeService;
    private NodeService nodeService;
    private NodeRef rootNodeRef;

    public void setStoreService(StoreService storeService)
    {
        this.storeService = storeService;
    }

    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }
    
    protected void onSetUpInTransaction() throws Exception
    {
        // create a first store directly
        StoreRef storeRef = storeService.createStore(StoreRef.PROTOCOL_WORKSPACE,
                "Test_" + System.currentTimeMillis());
        rootNodeRef = storeService.getRootNode(storeRef);
    }
    
    public void testSetUp() throws Exception
    {
        assertNotNull("Node Service not set", nodeService);
        assertNotNull("rootNodeRef not created", rootNodeRef);
    }
    
    private int countNodesById(NodeRef nodeRef)
    {
        String query =
                "select count(node.key.guid)" +
                " from " +
                NodeImpl.class.getName() + " node" +
                " where node.key.guid = ?";
        Session session = getSession();
        List results = session.createQuery(query)
            .setString(0, nodeRef.getId())
            .list();
        Integer count = (Integer) results.get(0);
        return count.intValue();
    }
    
    public void testCreateNodeNoProperties() throws Exception
    {
        // flush to ensure that the pure JDBC query will work
        NodeRef nodeRef = nodeService.createNode(rootNodeRef, "path1", Node.TYPE_CONTAINER);
        // count the nodes with the given id
        int count = countNodesById(nodeRef);
        assertEquals("Unexpected number of nodes present", 1, count);
    }
    
    public void testDelete() throws Exception
    {
        NodeRef nodeRef = nodeService.createNode(rootNodeRef, "path1", Node.TYPE_CONTAINER);
        int countBefore = countNodesById(nodeRef);
        assertEquals("Node not created", 1, countBefore);
        // delete it
        nodeService.deleteNode(nodeRef);
        int countAfter = countNodesById(nodeRef);
        // check
        assertEquals("Node not deleted", 0, countAfter);
    }
    
    private int countChildrenOfNode(NodeRef nodeRef)
    {
        String query =
                "select node.childAssocs" +
                " from " +
                NodeImpl.class.getName() + " node" +
                " where node.key.guid = ?";
        Session session = getSession();
        List results = session.createQuery(query)
            .setString(0, nodeRef.getId())
            .list();
        int count = results.size();
        return count;
    }
    
    /**
     * TODO: Fix test checks
     */
    public void testAddChild() throws Exception
    {
        // create a bogus reference
        NodeRef bogusChildRef = new NodeRef(rootNodeRef.getStoreRef(), "BOGUS");
        try
        {
            nodeService.addChild(rootNodeRef, bogusChildRef, "BOGUS_PATH");
            fail("Failed to detect invalid child node reference");
        }
        catch (InvalidNodeRefException e)
        {
            // expected
        }
        NodeRef nodeRef = nodeService.createNode(rootNodeRef, "pathA", Node.TYPE_CONTAINER);
//        int countBefore = countChildrenOfNode(rootNodeRef);
//        assertEquals("Root children count incorrect", 1, countBefore);
        // associate the two nodes
        nodeService.addChild(rootNodeRef, nodeRef, "pathB");
        // there should now be 2 child assocs on the root
//        int countAfter = countChildrenOfNode(rootNodeRef);
//        assertEquals("Root children count incorrect", 2, countAfter);
    }
    
    public void testRemoveChildByRef() throws Exception
    {
        NodeRef nodeRef = nodeService.createNode(rootNodeRef, "pathA", Node.TYPE_CONTAINER);
        nodeService.addChild(rootNodeRef, nodeRef, "pathB");
        nodeService.addChild(rootNodeRef, nodeRef, "pathC");
        // delete all the associations
        nodeService.removeChild(rootNodeRef, nodeRef);
    }
    
    public void testRemoveChildByName() throws Exception
    {
        NodeRef nodeRef = nodeService.createNode(rootNodeRef, "pathA", Node.TYPE_CONTAINER);
        nodeService.addChild(rootNodeRef, nodeRef, "pathB");
        nodeService.addChild(rootNodeRef, nodeRef, "pathC");
        // delete all the associations
        nodeService.removeChildren(rootNodeRef, "pathB");
    }
    
    public void testGetType() throws Exception
    {
        NodeRef nodeRef = nodeService.createNode(rootNodeRef, "pathA", Node.TYPE_CONTAINER);
        // get the type
        String type = nodeService.getType(nodeRef);
        assertEquals("Type mismatch", Node.TYPE_CONTAINER, type);
    }
    
    public void testProperties() throws Exception
    {
        Map<String, String> properties = new HashMap<String, String>(5);
        properties.put("PROPERTY1", "VALUE1");
        // add some properties to the root node
        nodeService.setProperties(rootNodeRef, properties);
        // set a single property
        nodeService.setProperty(rootNodeRef, "PROPERTY2", "VALUE2");
        
        // force a flush
        getSession().flush();
        getSession().clear();
        
        // now get them back
        Map checkMap = nodeService.getProperties(rootNodeRef);
        assertNotNull("Properties were not set/retrieved", checkMap);
        assertNotNull("Property value not set", checkMap.get("PROPERTY1"));
        assertNotNull("Property value not set", checkMap.get("PROPERTY2"));
        
        // get a single property direct from the node
        String valueCheck = nodeService.getProperty(rootNodeRef, "PROPERTY2");
        assertNotNull("Property value not set", valueCheck);
        assertEquals("Property value incorrect", "VALUE2", valueCheck);
    }
    
    public void testGetParents() throws Exception
    {
        NodeRef parent1Ref = nodeService.createNode(rootNodeRef, "P1", Node.TYPE_CONTAINER);
        NodeRef parent2Ref = nodeService.createNode(rootNodeRef, "P2", Node.TYPE_CONTAINER);
        NodeRef childRef = nodeService.createNode(parent1Ref, "PrimaryChild", Node.TYPE_CONTENT);
        nodeService.addChild(parent2Ref, childRef, "SecondaryChild");
        // get the child node's parents
        Collection<NodeRef> parents = nodeService.getParents(childRef);
        assertEquals("Incorrect number of parents", 2, parents.size());
        assertTrue("Expected parent not found", parents.contains(parent1Ref));
        assertTrue("Expected parent not found", parents.contains(parent2Ref));
        
        // check that we can retrieve the primary parent
        NodeRef primaryParentCheck = nodeService.getPrimaryParent(childRef);
        assertEquals("Primary parent not retrieved", parent1Ref, primaryParentCheck);
        
        // check that the root node returns a null primary parent
        NodeRef nullParent = nodeService.getPrimaryParent(rootNodeRef);
        assertNull("Expected null primary parent for root node", nullParent);
    }
    
    public void testGetChildren() throws Exception
    {
        NodeRef parentRef = nodeService.createNode(rootNodeRef, "P1", Node.TYPE_CONTAINER);
        NodeRef child1Ref = nodeService.createNode(parentRef, "PrimaryChild", Node.TYPE_CONTENT);
        NodeRef child2Ref = nodeService.createNode(rootNodeRef, "OtherChild", Node.TYPE_CONTENT);
        nodeService.addChild(parentRef, child2Ref, "SecondaryChild");
        // get the parent node's children
        Collection<NodeRef> children = nodeService.getChildren(parentRef);
        assertEquals("Incorrect number of children", 2, children.size());
        assertTrue("Expected child not found", children.contains(child1Ref));
        assertTrue("Expected child not found", children.contains(child2Ref));
    }
    
    /**
     * Creates a named association between two nodes
     * 
     * @return Returns an array of [source real NodeRef][target reference NodeRef][assoc name String]
     */
    private Object[] createAssociation() throws Exception
    {
        NodeRef sourceRef = nodeService.createNode(rootNodeRef, "N1", Node.TYPE_REAL);
        NodeRef targetRef = nodeService.createNode(rootNodeRef, "N2", Node.TYPE_REFERENCE);
        String assocName = "next";
        nodeService.createAssociation(sourceRef, targetRef, assocName);
        // done
        Object[] ret = new Object[] {sourceRef, targetRef, assocName};
        return ret;
    }
    
    public void testCreateAssociation() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        String assocName = (String) ret[2];
        try
        {
            // attempt the association in reverse
            nodeService.createAssociation(sourceRef, targetRef, assocName);
            fail("Incorrect node type not detected");
        }
        catch (RuntimeException e)
        {
            // expected
        }
        try
        {
            // attempt repeat
            nodeService.createAssociation(sourceRef, targetRef, assocName);
            fail("Duplicate assocation not detected");
        }
        catch (AssociationExistsException e)
        {
            // expected
        }
    }
    
    public void testRemoveAssociation() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        String assocName = (String) ret[2];
        // remove the association
        nodeService.removeAssociation(sourceRef, targetRef, assocName);
        // remake association
        nodeService.createAssociation(sourceRef, targetRef, assocName);
    }
    
    public void testGetAssociationTargets() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        String assocName = (String) ret[2];
        // get the association targets
        Collection<NodeRef> targets = nodeService.getAssociationTargets(sourceRef, assocName);
        assertEquals("Incorrect number of targets", 1, targets.size());
        assertTrue("Target not found", targets.contains(targetRef));
    }
    
    public void testGetAssociationSources() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        String assocName = (String) ret[2];
        // get the association targets
        Collection<NodeRef> sources = nodeService.getAssociationSources(targetRef, assocName);
        assertEquals("Incorrect number of sources", 1, sources.size());
        assertTrue("Source not found", sources.contains(sourceRef));
    }
    
    /**
     * Builds a web of nodes paths
     * <p>
     * Naming convention is:
     * <pre>
     * n2_p_n5
     * n4_n5
     * where
     *      n5 is the node number of the node
     *      n2 is the primary parent node number
     *      n4 is any other non-primary parent
     * </pre>
     * The following structure is used to name associations in the test:
     * <pre>
     * Level 0:     root
     * Level 1:     root_p_n1   root_p_n2
     * Level 2:     n1_p_n3     n2_p_n4     n1_n4       n2_p_n5
     * Level 3:     n3_p_n6     n4_n6       n5_p_n7
     * Level 4:     n6_p_n8     n7_n8
     * </pre>
     * 
     * @return Returns a map <code>NodeRef</code> instances keyed by node name
     */
    private Map<String, NodeRef> buildNodeGraph() throws Exception
    {
        // LEVEL 0
        // LEVEL 1
        NodeRef n1 = nodeService.createNode(rootNodeRef, "root_p_n1", Node.TYPE_CONTAINER);
        NodeRef n2 = nodeService.createNode(rootNodeRef, "root_p_n2", Node.TYPE_CONTAINER);
        // LEVEL 2
        NodeRef n3 = nodeService.createNode(n1, "n1_p_n3", Node.TYPE_CONTAINER);
        NodeRef n4 = nodeService.createNode(n2, "n2_p_n4", Node.TYPE_CONTAINER);
        nodeService.addChild(n1, n4, "n1_n4");
        NodeRef n5 = nodeService.createNode(n2, "n2_p_n5", Node.TYPE_CONTAINER);
        // LEVEL 3
        NodeRef n6 = nodeService.createNode(n3, "n3_p_n6", Node.TYPE_CONTAINER);
        nodeService.addChild(n4, n6, "n4_n6");
        NodeRef n7 = nodeService.createNode(n5, "n5_p_n7", Node.TYPE_CONTAINER);
        // LEVEL 4
        NodeRef n8 = nodeService.createNode(n6, "n6_p_n8", Node.TYPE_CONTAINER);
        nodeService.addChild(n7, n8, "n7_n8");
        // compile the map
        Map<String, NodeRef> ret = new HashMap<String, NodeRef>(13);
        ret.put("root", rootNodeRef);
        ret.put("n1", n1);
        ret.put("n2", n2);
        ret.put("n3", n3);
        ret.put("n4", n4);
        ret.put("n5", n5);
        ret.put("n6", n6);
        ret.put("n7", n7);
        ret.put("n8", n8);
        // done
        return ret;
    }
    
    /**
     * @see #buildNodeGraph() 
     */
    public void testGetPath() throws Exception
    {
        Map<String, NodeRef> nodes = buildNodeGraph();
        NodeRef n8Ref = nodes.get("n8");

        // get the primary node path for n8
        Path path = nodeService.getPath(n8Ref);
        assertEquals("Primary path incorrect",
                "/{}root_p_n1/{}n1_p_n3/{}n3_p_n6/{}n6_p_n8",
                path.toString());
    }
    
    /**
     * @see #buildNodeGraph() 
     */
    public void testGetPaths() throws Exception
    {
        Map<String, NodeRef> nodes = buildNodeGraph();
        NodeRef n8Ref = nodes.get("n8");

        long before = System.currentTimeMillis();
        // get all paths for n8
        Collection<Path> paths = nodeService.getPaths(n8Ref, false);
        assertEquals("Incorrect path count", 4, paths.size());
        // check that each path element has parent node ref, qname and child node ref
        for (Path path : paths)
        {
            // get the path elements
            for (Path.Element element : path)
            {
                assertTrue("Path element of incorrect type", element instanceof Path.ChildAssocElement);
                Path.ChildAssocElement childAssocElement = (Path.ChildAssocElement) element;
                ChildAssocRef ref = childAssocElement.getRef();
                assertNotNull("Parent node ref not set", ref.getParentRef());
                assertNotNull("QName not set", ref.getName());
                assertNotNull("Child node ref not set", ref.getChildRef());
            }
        }

        long after = System.currentTimeMillis();
        long delay = (after - before);
        // no more than 20ms
        assertTrue("getPaths(..., false) took " + delay + "ms", delay < 20);
        
        // get primary path for n8
        paths = nodeService.getPaths(n8Ref, true);
        assertEquals("Incorrect path count", 1, paths.size());
        
        // check that a cyclic path is detected - make n8_n2
        NodeRef n6Ref = nodes.get("n6");
        nodeService.addChild(n8Ref, n6Ref, "n8_n6");
        try
        {
            nodeService.getPaths(n8Ref, false);
            fail("Cyclic relationship not detected");
        }
        catch (CyclicChildRelationshipException e)
        {
            // expected
        }
        catch (StackOverflowError e)
        {
            fail("Cyclic relationship caused stack overflow");
        }
    }
}
