package spaceio.game.view.render;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.debug.Arrow;
import com.jme3.util.BufferUtils;
import com.jme3.util.TangentBinormalGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spaceio.core.engine.GeometryGenerators;
import spaceio.core.engine.Materials;
import spaceio.core.octree.Octant;
import spaceio.core.octree.Octinfo;
import spaceio.core.octree.Octree;
import spaceio.core.octree.OctreeListener;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Renderer extends AbstractAppState implements OctreeListener{

    private static final float SELECT_PRECISION = 0.0001f;
    private static final Logger logger = LoggerFactory.getLogger(Renderer.class.getCanonicalName());
    private SimpleApplication app;
    private AppStateManager stateManager;
    private Octree octree;
    private Node octantsScenegraphRoot;
    private Node selectionObjectScenegraphRoot;
    Arrow arrow; //TODO: MOVE ARROW TO THE SELECTION CONTROL
    Geometry arrowGeometry;

    Map<Integer, List<Octant>> nodes; //visible octants subdivided by material

    @Override
    public void setOctree(Octree tree){
        this.octree = tree;
    }

    @Override
    public void initialize(AppStateManager stateManager, Application app) {
        logger.info("Initialize");
        super.initialize(stateManager, app);
        this.app = (SimpleApplication) app;
        this.stateManager = stateManager;
        octantsScenegraphRoot = new Node("octants root node");
        selectionObjectScenegraphRoot = new Node("selection object root node");
        this.app.getRootNode().attachChild(octantsScenegraphRoot);
        this.app.getRootNode().attachChild(selectionObjectScenegraphRoot);
        this.nodes = new HashMap<>();

        arrow = new Arrow(Vector3f.UNIT_X);
        arrowGeometry = GeometryGenerators.putShape(arrow, ColorRGBA.Orange);
        this.app.getRootNode().attachChild(arrowGeometry);
    }

    @Override
    public void update(float tpf){
    }

    @Override
    public void onOctantGenerated(Octant o) {
        int mat = o.getMaterialType();
        if(mat == Materials.MAT_AIR){
            //do nothing, we don't show air
        } else {

            //If the material has no associated list, create the list
            if(nodes.get(mat) == null){
                nodes.put(mat, new ArrayList<Octant>());
                logger.info("Renderer: Created list for material " + mat);
            }
            //then add the node to the map;
            nodes.get(mat).add(o);
            logger.info("Renderer: Added octant " + o.getId() + " to list " + mat);

            compileMeshes();
        }
    }

    private void compileMeshes(){
        octantsScenegraphRoot.detachAllChildren();

        for(Integer i: nodes.keySet()){
            List<Octant> l = nodes.get(i);
            if(l.isEmpty()) break;

            FloatBuffer pos = BufferUtils.createFloatBuffer(12*6*l.size());
            FloatBuffer tex = BufferUtils.createFloatBuffer(8*6*l.size());
            FloatBuffer norm = BufferUtils.createFloatBuffer(12*6*l.size());
            ShortBuffer ind = BufferUtils.createShortBuffer(6*6*l.size());

            int c = 0;
            for(Octant o: l){
                o.data.compileArrays();
                pos.put(o.data.posArray);
                tex.put(o.data.texCoordsArray);
                norm.put(o.data.normArray);

                for(int j=0; j<o.data.indArray.length; j++){
                    short val = o.data.indArray[j];
                    val += 4*6*c;
                    ind.put(val);
                }
                c++;
            }

            Mesh mesh = new Mesh();
            mesh.setBuffer(VertexBuffer.Type.Position, 3, pos);
            mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, tex);
            mesh.setBuffer(VertexBuffer.Type.Normal, 3, norm);
            mesh.setBuffer(VertexBuffer.Type.Index, 3, ind);
            TangentBinormalGenerator.generate(mesh);
            mesh.updateBound();

            Geometry g = new Geometry("Mesh" + i, mesh);
            g.setMaterial(stateManager.getState(Materials.class).getMaterial(i));

            octantsScenegraphRoot.attachChild(g);
        }
    }

    @Override
    public void onOctantDeleted(Octant o) {

        boolean found = removeOctant(o);
        if(found){
            logger.info("Removed octant " + o.getId() + " from nodes");
        }
        compileMeshes();
    }

    @Override
    public void onOctantMaterialChanged(Octant o) {

        //find the node among the Octants
        boolean found = removeOctant(o);

        if(found){
            logger.info("Removed octant " + o.getId() + " from nodes");
        }

        onOctantGenerated(o);
    }

    private boolean removeOctant(Octant o){
        for(List<Octant> l: nodes.values()){
            for(Octant c: l){
                if(c.getId() == o.getId()){
                    l.remove(c);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Given a CollisionResult object, calculates the Octinfo related to the
     * collision.
     * @param collisionResult
     * @return
     */
    public Octinfo getSelectionOctinfo(CollisionResult collisionResult) {
        if(octree==null)
            return null;
        //calculate a point on the octant just inside its bounds
        Vector3f collisionPoint = new Vector3f(collisionResult.getContactPoint());
        Vector3f collisionNormal = new Vector3f(collisionResult.getContactNormal());
        collisionNormal.negateLocal().multLocal(SELECT_PRECISION); //floats are reliable up to the 6th digit
        collisionPoint.addLocal(collisionNormal);
        return octree.getOctinfo(collisionPoint, stateManager.getState(OctantSelectionManager.class).getStep());
    }

    public void refreshSelection(){
        if(octree!= null){

            //See what object we have under the cursor
            CollisionResults results = new CollisionResults();
            Ray ray = new Ray(app.getCamera().getLocation(), app.getCamera().getDirection());

            octantsScenegraphRoot.collideWith(ray, results);
            if(results.size()>0){
                Octinfo oi = getSelectionOctinfo(results.getClosestCollision());
                stateManager.getState(OctantSelectionManager.class).updateSelection(results.getClosestCollision(), oi);

                if(arrow!= null){
                    Vector3f contactNormal = new Vector3f(results.getClosestCollision().getContactNormal());
                    arrow.setArrowExtent(contactNormal.mult(0.1f));
                }
                if(arrowGeometry!=null){
                    arrowGeometry.setLocalTranslation(results.getClosestCollision().getContactPoint());
                }
            }
        }
    }
}
