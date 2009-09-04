package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.transactionrequired;
import se.scalablesolutions.akka.state.*;

@transactionrequired
public class PersistentStatefulNested {
  private PersistentState factory = new PersistentState();
  private TransactionalMap mapState =       factory.newMap(new CassandraStorageConfig());
  private TransactionalVector vectorState = factory.newVector(new CassandraStorageConfig());;
  private TransactionalRef refState =       factory.newRef(new CassandraStorageConfig());


  public String getMapState(String key) {
    return (String) mapState.get(key).get();
  }


  public String getVectorState(int index) {
    return (String) vectorState.get(index);
  }

  public int getVectorLength() {
    return vectorState.length();
  }

  public String getRefState() {
    if (refState.isDefined()) {
      return (String) refState.get().get();
    } else throw new IllegalStateException("No such element");
  }


  public void setMapState(String key, String msg) {
    mapState.put(key, msg);
  }


  public void setVectorState(String msg) {
    vectorState.add(msg);
  }


  public void setRefState(String msg) {
    refState.swap(msg);
  }


  public String success(String key, String msg) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    return msg;
  }


  public String failure(String key, String msg, PersistentFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    failer.fail();
    return msg;
  }

  
  public void thisMethodHangs(String key, String msg, PersistentFailer failer) {
    setMapState(key, msg);
  }
}

