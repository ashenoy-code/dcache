package org.dcache.services.info.base;


import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

/**
 * A StateComposite is an aggregation of zero or more StateComponents.  StateComposites
 * form the branch nodes within the dCache state tree.
 * <p>
 * A Mortal StateComposite has a minimum lifetime when created.  The expiry date will be
 * adjusted to match any added Mortal children: the branch will always persist whilst it
 * contains any children.
 * <p>
 * An Ephemeral StateComposite has no lifetime: it will persist without having fixed
 * lifetime.  However an Ephemeral StateComposite will not prevent an ancestor StateComposite
 * that is Mortal from expiring.  In general, a StateComposite that contains <i>only</i>
 * Ephemeral children should also be Ephemeral; all other StateComposites should be Mortal.  
 * <p>
 * A StateComposite also maintains a record of the earliest any of its children (or children of
 * children) will expire.  This is an optimisation, allowing a quick determination when
 * a tree should next be purged and, with any subtree, whether it is necessary to purge that
 * subtree.
 * 
 * @author Paul Millar <paul.millar@desy.de>
 */
public class StateComposite implements StateComponent {

	private static final Logger _log = Logger.getLogger(StateComposite.class);
	
	
	/** Minimum lifetime for on-the-fly created StateComposites, in seconds */  
	private static final long DEFAULT_LIFETIME = 10;
	
	private final Map<String, StateComponent> _children = new HashMap<String, StateComponent>();
	private StatePersistentMetadata _metadataRef;
	private Date _earliestChildExpiry = null;
	private Date _whenIShouldExpire;
	private boolean _isEphemeral;
	
	/**
	 * The constructor for public use: a StateComposite with a finite lifetime.
	 * 
	 * @param lifetime the minimum duration, in seconds, that this StateComposite
	 * should persist.
	 */
	public StateComposite( long lifetime) {
		
		if( lifetime < 0)
			lifetime = 0;
		
		becomeMortal( lifetime);
		_metadataRef = null;  // Set when added to state tree
	}
	
	
	/**
	 * Create an Ephemeral StateComposite.  These should <i>only</i> be used
	 * when they are to contain only Ephemeral children.  Normally StateComposites
	 * should be created Mortal.
	 */
	public StateComposite() {
		becomeEphemeral();
		_metadataRef = null; // Set when added to state tree
	}

	/**
	 * Our private usage below: build a new Mortal StateComposite with a
	 * link to persistentMetadata.
	 * 
	 * @param ref the corresponding StatePersistentMetadata object.
	 * @param lifetime the minimum lifetime of this object, in seconds.
	 */
	private StateComposite( StatePersistentMetadata persistentMetadata, long lifetime) {
		becomeMortal( lifetime);
		_metadataRef = persistentMetadata;
	}
	
	/**
	 * Build an Immortal StateComposite with specific metadata link.
	 * This should only be used by the State singleton. 
	 * @param persistentMetadata the top-level metadata.
	 */
	protected StateComposite( StatePersistentMetadata persistentMetadata) {
		becomeImmortal();
		_metadataRef = persistentMetadata;		
	}

	/**
	 * Possibly update our belief of the earliest time that a Mortal child StateComponent
	 * will expire.  It is safe to call this method with all child Dates: it will
	 * update the _earliestChildExpiry Date correctly.
	 * @param newDate  the expiry Date of a Mortal child StateComponent
	 */
	private void updateEarliestChildExpiryDate( Date newDate) {
		if( newDate == null)
			return;
		
		if( _earliestChildExpiry == null || newDate.before( _earliestChildExpiry))
			_earliestChildExpiry = newDate;
	}
	
	/**
	 * @return the time when the earliest child will expire, or null if we have
	 * no Mortal children.
	 */
	public Date getEarliestChildExpiryDate() {
		return _earliestChildExpiry != null ? new Date(_earliestChildExpiry.getTime()) : null;
	}
	

	/**
	 * Update our whenIShouldExpire date.  If the new date is before the existing
	 * one it is ignored.
	 * @param newDate  the new whenIShouldExpire date
	 */
	private void updateWhenIShouldExpireDate( Date newDate) {
		if( newDate == null)
			return;
		
		if( _whenIShouldExpire == null || newDate.after( _whenIShouldExpire))
			_whenIShouldExpire = newDate;
	}
	



	
	/**
	 * Return a cryptic string describing this StateComposite.
	 */
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("StateComposite <");
		sb.append( isMortal() ? "+" : isEphemeral() ? "*" : "#" );
		sb.append("> {");
		sb.append( _children.size());
		sb.append("}");

		return sb.toString();
	}

	/**
	 * When we should expire.
	 */
	public Date getExpiryDate() {
		return _whenIShouldExpire != null ? new Date( _whenIShouldExpire.getTime()) : null;
	}
	
	/**
	 *  This function checks whether our parent should expunge us.
	 */
	public boolean hasExpired() {
		Date now = new Date();
		
		return _whenIShouldExpire != null ? !now.before(_whenIShouldExpire) : false;
	}
	
	/**
	 * Make sure we never expire.
	 */
	private void becomeImmortal() {		
		_isEphemeral = false;
		_whenIShouldExpire = null; 
	}
	
	/**
	 * Switch behaviour to be Ephemeral.  That is, don't expire automatically but
	 * don't prevent Mortal parent(s) from expiring. 
	 */
	private void becomeEphemeral() {
		_isEphemeral = true;
		_whenIShouldExpire = null; 		
	}

	/**
	 * Initialise our expiry time to some point in the future.
	 * @param lifetime the time, in seconds.
	 */
	private void becomeMortal( long lifetime) {
		_whenIShouldExpire = new Date( System.currentTimeMillis() + TimeUnit.SECONDS.toMillis( lifetime)); 
	}
	
	

	/**
	 * Apply the visitor pattern over our children.
	 * <p>
	 * Interesting aspects:
	 * <ul>
	 * <li> Visiting over all child only happens once the <tt>start</tt> path has been
	 * exhausted
	 * <li> There are five StateVisitor call-backs from this StateComponent
	 * </ul>
	 * 
	 * The standard call-backs are:
	 * <ul>
	 * <li>visitCompositePreDescend() called before visiting children.
	 * <li>visitCompositePreLastDescend() called before visiting the last child.
	 * <li>visitCompositePostDescend() called after visiting children.
	 * </ul>
	 * 
	 * The <tt>start</tt> path allows the client to specify a point within the State tree
	 * to start visiting.  Iterations down to that level call a different set of Visitor
	 * call-backs: visitCompositePreSkipDescend() and visitCompositePostSkipDescend().
	 * These are equivalent to the non-<tt>Skip</tt> versions and allow the StateVisitor
	 * to represent the skipping down to the starting point, or not.
	 *
	 * @param path the path to the current position in the State.
	 * @param start the residual path to skip.
	 * @param visitor the object that implements the StateVisitor class.
	 */
	public void acceptVisitor(StatePath path, StatePath start, StateVisitor visitor) {
		if( _log.isDebugEnabled())
			_log.debug( "acceptVisitor( " + (path != null ? path : "(null)") + ", " + (start != null ? start : "(null)") + " )");

		Map<String,String> branchMetadata = getMetadataInfo();

		if( start != null) {
			String childName = start.getFirstElement();
						
			if( _children.containsKey( childName)) {
				visitor.visitCompositePreSkipDescend( path, branchMetadata);
				
				StateComponent child = _children.get( childName);
				StatePath childStartPath = start != null ? start.childPath() : null;
				child.acceptVisitor( buildChildPath( path, childName), childStartPath, visitor);
				
				visitor.visitCompositePostSkipDescend( path, branchMetadata);
				
			} else {
				
				if( _log.isDebugEnabled())
					_log.debug( this.toString() + " no children when accepting for " + start);

				/**
				 * No data is available at this level.  This is because the
				 * client has specified a start-path that (currently) doesn't exist.
				 * The path may be "correct" and will be filled with data "soon",
				 * or may be "incorrect" and data will never appear here.
				 * <p>
				 * Since we cannot distinguish between the two, we silently fail. 
				 */
			}
			return;
		}

		visitor.visitCompositePreDescend( path, branchMetadata);

		// Iterate over our children
		for(Iterator<Map.Entry<String, StateComponent>> itr = _children.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, StateComponent> mapEntry = itr.next();

			String childName = mapEntry.getKey();
			StateComponent child = mapEntry.getValue();

			if (!itr.hasNext())
				visitor.visitCompositePreLastDescend( path, branchMetadata);

			StatePath childStartPath = start != null ? start.childPath() : null;
			child.acceptVisitor( buildChildPath( path, childName), childStartPath, visitor);
		}

		visitor.visitCompositePostDescend( path, branchMetadata);
	}
	
	
	/**
	 * Simulate the effects of the StateTransition, so allowing the StateVisitor to visit the dCache
	 * State after the transition has taken effect.
	 */
	public void acceptVisitor( StateTransition transition, StatePath ourPath, StatePath startPath, StateVisitor visitor) {

		if( _log.isDebugEnabled())
			_log.debug( "acceptVisitor( " + (transition != null ? "not null" : "(null)")+ ", " + (ourPath != null ? ourPath : "(null)") + ", " + (startPath != null ? startPath : "(null)") + " )");
		
		assert( transition != null);

		Map<String,String> branchMetadata = getMetadataInfo();
		
		StateChangeSet changeSet = transition.getStateChangeSet( ourPath);
		
		/** If start is not yet null, iterate down directly */
		if( startPath != null) {
			String childName = startPath.getFirstElement();
			
			if( haveChild( changeSet, childName)) {
				visitor.visitCompositePreSkipDescend(ourPath, branchMetadata);
				visitNamedChild( visitor, ourPath, childName, startPath, transition, changeSet);
				visitor.visitCompositePostSkipDescend(ourPath, branchMetadata);
			} else {
				
				if( _log.isDebugEnabled())
					_log.debug( this.toString() + " no children when accepting for " + startPath);

				/**
				 * No data is available at this level.  This is because the
				 * client has specified a start-path that (currently) doesn't exist.
				 * The path may be "correct" and will be filled with data "soon",
				 * or may be "incorrect" and data will never appear here.
				 * <p>
				 * Since we cannot distinguish between the two, we silently fail. 
				 */
			}
			return;
		}

		visitor.visitCompositePreDescend(ourPath, branchMetadata);

		Set<String> children = buildChildrenList( changeSet);
		
		// Iterate over our children
		for(Iterator<String> itr = children.iterator(); itr.hasNext();) {
			String childName = itr.next();

			if (!itr.hasNext())
				visitor.visitCompositePreLastDescend(ourPath, branchMetadata);

			visitNamedChild( visitor, ourPath, childName, null, transition, changeSet);
		}

		visitor.visitCompositePostDescend(ourPath, branchMetadata);
	}


	/**
	 * Visit a specific child.  This is somewhat tricky as we must be careful, when there is
	 * a transition, which child we visit.
	 */
	private void visitNamedChild( StateVisitor visitor, StatePath ourPath, String childName, StatePath startPath, StateTransition transition, StateChangeSet changeSet) {

		StateComponent childToVisit = _children.get( childName);

		if( changeSet != null) {			
			/**
			 *  A fresh child is one that is either new or an updated value for
			 *  an existing child.
			 */
			StateComponent freshChild = changeSet.getFreshChildValue( childName);

			/**  
			 *  If the child is new then _children.containsKey( childName) is false.   If
			 *  this is so, we always visit it.
			 *  
			 *  If the is an updated value then things are slightly more tricky.
			 *  If updated child is a StateValue (i.e., a metric), then always
			 *  visit the updated value.  If the child is a StateComposite (a branch) then
			 *  visit the existing child.  This is because changes are recorded against
			 *  the existing StateComposites whenever an existing StateComposite exists.
			 *
			 *  NB.  Here we assume that childToVisit != null is
			 *       equivalent to _children.containsKey( childName).
			 *       In the absence of bugs, this is true since null
			 *       is not an allowed StateComponent
			 */
			if( freshChild != null && !(childToVisit != null && childToVisit instanceof StateComposite))
				childToVisit = freshChild;
		}

		if( childToVisit == null) {
			_log.error("Tried to visit null child in " + ourPath != null ? ourPath : "(top)");
			return;
		}

		StatePath childStartPath = startPath != null ? startPath.childPath() : null;
		childToVisit.acceptVisitor( transition, buildChildPath( ourPath, childName), childStartPath, visitor);
	}
	
	/**
	 * Obtain a child based on StateTransition.  If transition is null, only get this
	 * child from local store, otherwise StateTransition will be considered.
	 * @param ourPath the StatePath for this StateComposite
	 * @param transition the transition under consideration, or null
	 * @param childName the name of the child StateComponent
	 * @return the value of the child after transition.
	 */
	private boolean haveChild( StateChangeSet changeSet, String childName) {
		
		boolean currentlyHaveChild = _children.containsKey( childName);

		if( changeSet != null) {
			
			if( currentlyHaveChild) {
				if( changeSet.childIsRemoved( childName))
					return false;
				
			} else {				
				if( changeSet.childIsNew( childName))
					return true;				
			}
		}
		
		return currentlyHaveChild;
	}
		

	/**
	 * Build a Set of our children, potentially taking into account a StateTransition.
	 * The transition may be null, in which case this is equivalent to <code>_children.keySet()</code>  
	 */
	private Set<String> buildChildrenList( StateChangeSet changeSet) {
		if( changeSet == null)
			return _children.keySet();

		Set<String> allChildren = new HashSet<String>();
		
		Set<String> newChildren = changeSet.getNewChildren();
			
		if( newChildren != null)
			allChildren.addAll( newChildren);
			
		allChildren.addAll( _children.keySet());
		
		Set<String> removedChildren = changeSet.getRemovedChildren();

		if( removedChildren != null)
			allChildren.removeAll( removedChildren);
		
		return allChildren;
	}
	
	
	/**
	 * Apply a transition to our current state.  Children are added, updated or removed based on
	 * the supplied transition.
	 * @param ourPath the path to this within dCache tree, or null for top-most StateComposite
	 * @param transition the StateTransition to apply
	 */
	public void applyTransition( StatePath ourPath, StateTransition transition) {

		StateChangeSet changeSet = transition.getStateChangeSet( ourPath);
		
		if( changeSet == null) {
			_log.warn( "cannot find StateChangeSet for path " + ourPath + ". Something must have gone wrong.");
			return;
		}

		Date newExpDate = changeSet.getWhenIShouldExpireDate();
		updateWhenIShouldExpireDate( newExpDate);
		if( newExpDate == null)
			_log.debug("getWhenIShouldExpireDate() returned null: no Mortal children?");
		
		if( changeSet.haveImmortalChild())
			becomeImmortal(); // this is currently irreversible

		// First, remove those children we should remove.
		for( String childName : changeSet.getRemovedChildren()) {
			if( _log.isDebugEnabled())
				_log.debug("removing child " + childName);
			_children.remove( childName);
		}

		// Then update our existing children.
		for( String childName : changeSet.getUpdatedChildren()) {
			StateComponent updatedChildValue = changeSet.getUpdatedChildValue( childName);

			if( updatedChildValue == null) {
				_log.error( "Attempting to update " + childName + " in " + ourPath + ", but value is null; wilfully ignoring this.");
				continue;
			}
			
			if( _log.isDebugEnabled())
				_log.debug("updating child " + childName + ", updated value " + updatedChildValue.toString());
			
			addComponent( childName, updatedChildValue);
		}


		// Finally, add all new children.
		for( String childName : changeSet.getNewChildren()) {
			StateComponent newChildValue = changeSet.getNewChildValue( childName);

			if( _log.isDebugEnabled())
				_log.debug("adding new child " + childName + ", new value " + newChildValue.toString());
			
			addComponent( childName, newChildValue);
		}
		
		// Now, which children should we iterate into?
		for( String childName : changeSet.getItrChildren()) {
			StateComponent child = _children.get( childName);
			
			if( child == null) {
				if( !changeSet.getRemovedChildren().contains( childName))
					_log.error( "Whilst in "+ourPath+", avoided attempting to applyTransition() on missing child " + childName);
				continue;
			}
			
			child.applyTransition( buildChildPath(ourPath, childName), transition);
		}
		
		recalcEarliestChildExpiry();
	}
	
	
	/**
	 * Recalculate _earliestChildExpiryDate() by asking our children for their earliest expiring
	 * child.
	 * TODO: this isn't always necessary, but it's hard to know when.  Also, it isn't clear that the
	 * cost of figuring out when it is necessary is less than the CPU time saved by always recalculating.
	 */
	private void recalcEarliestChildExpiry() {
		
		_earliestChildExpiry = null; // A forceful reset
		
		for( StateComponent child : _children.values()) {
			
			Date earliestExpires = child.getEarliestChildExpiryDate();

			if( earliestExpires != null)
				updateEarliestChildExpiryDate( earliestExpires);
			
			if( child.isMortal())
				updateEarliestChildExpiryDate( child.getExpiryDate());			
		}
	}
	
	
	/**
	 * Look up persistent metadata reference for child and return it.  If none is
	 * available, null is returned.
	 * @param childName the name of the child.
	 * @return a StatePersistentMetadata entry, or null if none is appropriate.
	 */
	private StatePersistentMetadata getChildMetadata( String childName) {
		return _metadataRef == null ? null : _metadataRef.getChild( childName);
	}
	
	/**
	 * @return our metadata info, if there is any, otherwise null.
	 */
	private Map<String,String> getMetadataInfo() {
		return _metadataRef == null ? null : _metadataRef.getMetadata();
	}
	
	
	/**
	 * Add a new component to our list of children.
	 * <p>
	 * @param childName the name under which this item should be recorded
	 * @param newChild the StateComponent to be stored.
	 */
	private void addComponent(String childName, StateComponent newChild) {

		StateComponent existingChild = _children.get( childName);
		
		/**
		 *  If we're added a StateComposite, we must be a little more careful!
		 */
		if( newChild instanceof StateComposite) {
			StateComposite newComposite = (StateComposite) newChild;
			
			/**
			 * Copy across all existing children that don't clash.  Those with
			 * the same name are updates for those children, so we want to go with
			 * the values under the newComposite.
			 */
			if( existingChild instanceof StateComposite) {
				StateComposite existingComposite = (StateComposite) existingChild;
				
				// Copy across the existingComposite's children over to the newComposite
				for( Map.Entry<String,StateComponent> entry : existingComposite._children.entrySet())						
					if( !newComposite._children.containsKey(entry.getKey()))
						newComposite._children.put(entry.getKey(), entry.getValue());
				
				// ... and details of the dates...
				newComposite.updateEarliestChildExpiryDate( existingComposite.getEarliestChildExpiryDate());
				newComposite.updateWhenIShouldExpireDate( existingComposite.getExpiryDate());
			}
		}

		_children.put( childName, newChild);
		
		if( _log.isDebugEnabled())
			_log.debug( "Child "+ childName + " now " + _children.get(childName).toString());
	}

	
	
	/**
	 * Update a StateTransition object so a new StateComponent will be added to dCache's state.  The
	 * changes are recorded in StateTransition so they can be applied later.
	 * @param ourPath the StatePath to this StateComposite.
	 * @param newComponentPath the StatePath to this StateComponent, relative to this StateComposition 
	 * @param newComponent the StateComponent to add.
	 * @param transition the StateTransition in which we will record these changes
	 */
	public void buildTransition( StatePath ourPath, StatePath newComponentPath, StateComponent newComponent, StateTransition transition) throws MetricStatePathException {

		String childName = newComponentPath.getFirstElement();
		StateChangeSet changeSet = transition.getOrCreateChangeSet( ourPath);
		
		/* If we are mortal and the new child is too, check we don't expire too soon */
		if( this.isMortal() && newComponent.isMortal()) {
			Date newComponentExpiryDate = newComponent.getExpiryDate();
			changeSet.recordNewWhenIShouldExpireDate( newComponentExpiryDate);			
		}
		
		// All parents of an Immortal Child should know not to expire.
		if( newComponent.isImmortal())
			changeSet.recordChildIsImmortal();
		
		/**
		 * If newComponent is one of our children, process it directly.
		 */
		if( newComponentPath.isSimplePath()) {
			
			if( _children.containsKey(childName))
				changeSet.recordUpdatedChild( childName, newComponent);
			else
				changeSet.recordNewChild( childName, newComponent);
			
			if( newComponent instanceof StateComposite) {
				StateComposite newComposite = (StateComposite) newComponent;
				newComposite._metadataRef = getChildMetadata(childName);
			}


			return;
		}

		/**
		 * Otherwise, iterate down; if possible, through the existing tree, otherwise through
		 * a new StateComposite.
		 */
		StateComponent child = _children.get(childName);
			
		if( child == null) {
			
			// Perhaps we're already adding a StateComposite with this transition?
			child = changeSet.getNewChildValue( childName);
			
			if( child == null) {
				// No? OK, create a new NewComposite and record it.
				child = new StateComposite( getChildMetadata( childName), DEFAULT_LIFETIME);
				changeSet.recordNewChild( childName, child);
			}
		}
		
		/**
		 * Even if we didn't change anything, record that we're about to iterate down and do so.
		 */
		changeSet.recordChildItr( childName);
		child.buildTransition( buildChildPath(ourPath, childName), newComponentPath.childPath(), newComponent, transition);
	}
	
	
	/**
	 * Check whether the specified StatePathPredicate has been triggered by the given StateTransition.
	 * <p>
	 * If none of our children match the top-level element of the predicate, then the answer is definitely
	 * not triggered.
	 * <p>
	 * If a child matches but there are more elements to consider, iterate down: ask the child whether the
	 * predicate has been triggered with one less element in the predicate.
	 * <p>
	 * If the predicate is simple (that is, contains only a single element) then the predicate is
	 * triggered depending on the activity of our children under the StateTransition.
	 * @param ourPath  Our path
	 * @param predicate the predicate we are to check.
	 * @param transition the StateTransition to consider.
	 * @return true if the transition has triggered this predicate, false otherwise
	 */
	public boolean predicateHasBeenTriggered( StatePath ourPath, StatePathPredicate predicate, StateTransition transition) throws MetricStatePathException {

		if( _log.isDebugEnabled())
			_log.debug("entering ("+ (ourPath != null ? ourPath.toString() : "(null)") +", " + (predicate != null ? predicate.toString() : "(null)") + ")");

		StateChangeSet changeSet = transition.getStateChangeSet( ourPath);
		
		if( changeSet == null)
			return false;
		
		// Scan through the list of new children first.
		Collection<String> newChildren = changeSet.getNewChildren();
		
		if( newChildren != null) {
			for( String newChildName : newChildren) {

				if( !predicate.topElementMatches(newChildName)) // ignore unrelated children.
					continue;

				if( predicate.isSimplePath())
					return true; // a new child always triggers a predicate

				/**
				 *  Ask this child whether the predicate is triggered.  If the child says "yes", we
				 *  concur.
				 */ 
				StateComponent child = changeSet.getNewChildValue( newChildName);				
				if( child.predicateHasBeenTriggered( buildChildPath( ourPath, newChildName), predicate.childPath(), transition))
					return true;
				
				// Carry on searching...
			}
		}
		
		// Scan through our existing children
		for( String childName : _children.keySet()) {
			StateComponent child = _children.get( childName);
			
			// If we've done nothing, it can't have changed.
			if( !changeSet.hasChildChanged( childName))
				continue;
			
			// ignore unrelated children
			if( !predicate.topElementMatches(childName))
				continue;

			/**
			 * If predicate's last element is one of our children... 
			 */
			if( predicate.isSimplePath()) {
				// Check various options:
				
				// Removed children always triggers a predicate.
				if( changeSet.childIsRemoved( childName))
					return true; 
				
				// Has child changed "significantly" ?
				StateComponent updatedChildValue = changeSet.getUpdatedChildValue( childName);				
				if( updatedChildValue != null && !child.equals(updatedChildValue))
					return true;
			} else {
				// ... otherwise, try iterating down.
				if( child.predicateHasBeenTriggered( buildChildPath( ourPath, childName), predicate.childPath(), transition))
					return true;
			}
		}
		
		return false;
	}

	
	public boolean isEphemeral() {
		return _whenIShouldExpire == null && _isEphemeral;
	}
	
	public boolean isImmortal() {
		return _whenIShouldExpire == null && !_isEphemeral;
	}
	
	public boolean isMortal() {
		return _whenIShouldExpire != null;
	}
	
	
	/**
	 * Build a child's StatePath, taking into account that a path may be null
	 * (this one-liner is repeated fairly often)
	 * @param ourPath our current path, or null if we are the root StateComposite
	 * @param childName the name of the child.
	 * @return
	 */
	private StatePath buildChildPath( StatePath ourPath, String childName) {
		return ourPath != null ? ourPath.newChild(childName) : new StatePath( childName);
	}
	
	/**
	 * Ostensibly, we iterate over all children to find Mortal children that should be
	 * removed.  In practise, cached knowledge of Mortal child expiry Dates means this
	 * iterates over only those StateComponents that contain children that have actually
	 * expired.
	 *  
	 * @param ourPath
	 * @param transition
	 * @param forced
	 */
	public void buildRemovalTransition( StatePath ourPath, StateTransition transition, boolean forced) {
		Date now = new Date();

		if( _log.isDebugEnabled())
			_log.debug("entering buildRemovalTransition( "+ourPath+", ..)");
				
		// Check each child in turn:
		for( Map.Entry<String, StateComponent>entry : _children.entrySet()) {
			
			boolean shouldRemoveThisChild = forced;
			boolean shouldItr = forced;

			StateComponent childValue = entry.getValue();
			String childName = entry.getKey();

			// If *this* child has expired, we should mark it as To Be Removed.
			if( childValue.hasExpired()) {
				if( _log.isDebugEnabled())
					_log.debug("registering "+childName+" (in path "+ourPath+") for removal.");

				shouldRemoveThisChild = shouldItr = true;
			}			

			// If *this* child has some child that has expired, iterate down.
			Date childExp = childValue.getEarliestChildExpiryDate();
			if( childExp != null && now.after( childExp))
				shouldItr = true;
			
			if( shouldItr) {				
				StateChangeSet changeSet = transition.getOrCreateChangeSet( ourPath);

				if( shouldRemoveThisChild)
					changeSet.recordRemovedChild( childName);
					
				changeSet.recordChildItr( childName);
				childValue.buildRemovalTransition( buildChildPath(ourPath, childName), transition, shouldRemoveThisChild);				
			}
		}
	}


	/**
	 * Return a hash-code that honours the equals() / hashCode() contract.
	 */
	@Override
	public int hashCode() {
		return _children.hashCode();
	}
	
	/**
	 * Override the public equals method.  All StateComposites are considered equal if
	 * they have the same children are the same type and have the same expiry date
	 * (if mortal).
	 * 
	 * This is significant for when considering whether a StatePredicate has been triggered.
	 */
	@Override
	public boolean equals( Object other) {		
		if( other == this)
			return true;

		if( !(other instanceof StateComposite))
			return false;

		StateComposite otherSc = (StateComposite) other;

		return otherSc._children.equals( _children);
	}

}
