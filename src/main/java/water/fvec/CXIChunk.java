package water.fvec;

import water.AutoBuffer;
import water.H2O;
import water.MemoryManager;
import water.UDP;
import water.parser.DParseTask;

import java.util.Iterator;

/**
 * Created by tomasnykodym on 3/18/14.
 * Sparse chunk.
 */
public class CXIChunk extends Chunk {
  protected transient int _elsz; // byte size of stored value
  protected transient int _elsz_log; //
  protected transient int _idsz; // byte size of stored (chunk-relative) row nums
  protected static final int OFF = 6;
  protected transient int _lastOff = OFF;


  private static final long [] NAS = {C2Chunk._NA,C4Chunk._NA,C8Chunk._NA};

  protected CXIChunk(int len, int nzs, int esz){
    _len = len;
    _elsz = esz;
    int x = _elsz, log = 0;
    while(x > 1){
      x = x >> 1;
      ++log;
    }
    _elsz_log = log;
    assert (_elsz == 0 || _elsz == 1 || _elsz == 2 || _elsz == 4 || _elsz == 8);
    _idsz = (len >= 65535)?4:2;
    byte[] buf = MemoryManager.malloc1((nzs)*(_idsz+esz)+OFF); // 2 bytes row, 2 bytes val
    UDP.set4(buf,0,len);
    byte b = (byte)_idsz;
    buf[4] = b;
    buf[5] = (byte)_elsz;
    _mem = buf;
  }

  protected CXIChunk(long[] ls, int[] xs, int[] ids, int len, int nz, int elementSz){
    this(len,nz,elementSz);
    int off = OFF;
    final int inc = (_elsz + _idsz);
    for( int i=0; i<nz; i++, off += inc ) {
      if(_idsz == 2)
        UDP.set2(_mem,off,(short)ids[i]);
      else
        UDP.set4(_mem,off,ids[i]);
      if(_elsz == 0)continue;
      assert xs[i] == Integer.MIN_VALUE || xs[i] >= 0:"unexpected exponent " + xs[i]; // assert we have int or NA
      final long lval = xs[i] == Integer.MIN_VALUE?NAS[_elsz_log-1]:ls[i]*DParseTask.pow10i(xs[i]);
      switch(elementSz){
        case 1:
          _mem[off+_idsz] = (byte)lval;
          break;
        case 2:
          short sval = (short)lval;
          UDP.set2(_mem,off+_idsz,sval);
          break;
        case 4:
          int ival = (int)lval;
          UDP.set4(_mem, off+_idsz, ival);
          break;
        case 8:
          UDP.set8(_mem, off+_idsz, lval);
          break;
        default:
          throw H2O.unimpl();
      }
    }
    assert off==_mem.length;
  }

  @Override public final boolean isSparse() {return true;}
  @Override public final int sparseLen(){return (_mem.length - OFF) / (_elsz + _idsz);}
  @Override public final int nonzeros(int [] arr){
    int len = sparseLen();
    int off = OFF;
    final int inc = _elsz + 2;
    for(int i = 0; i < len; ++i, off += inc) arr[i] = UDP.get2(_mem, off)&0xFFFF;
    return len;
  }

  @Override boolean set_impl(int idx, long l)   { return false; }
  @Override boolean set_impl(int idx, double d) { return false; }
  @Override boolean set_impl(int idx, float f ) { return false; }
  @Override boolean setNA_impl(int idx)         { return false; }

  @Override protected long at8_impl(int idx) {
    int off = findOffset(idx);
    if(getId(off) != idx)return 0;
    long v = getIValue(off);
    if( v== NAS[_elsz_log-1])throw new IllegalArgumentException("at8 but value is missing");
    return v;
  }
  @Override protected double atd_impl(int idx) {
    int off = findOffset(idx);
    if(getId(off) != idx)return 0;
    long v =  getIValue(off);
    return (v == NAS[_elsz_log-1])?Double.NaN:v;
  }

  @Override protected boolean isNA_impl( int i ) {
    int off = findOffset(i);
    if(getId(off) != i)return false;
    return getIValue(off) == NAS[_elsz_log-1];
  }

  @Override boolean hasFloat ()                 { return false; }

  @Override NewChunk inflate_impl(NewChunk nc) {
    final int len = sparseLen();
    nc._len2 = _len;
    nc._len = sparseLen();
    nc._ls = MemoryManager.malloc8 (len);
    nc._xs = MemoryManager.malloc4 (len);
    nc._id = MemoryManager.malloc4 (len);
    int off = OFF;
    for( int i = 0; i < len; ++i, off += _idsz + _elsz) {
      nc._id[i] = getId(off);
      long v = getIValue(off);
      if(v == NAS[_elsz_log-1]){
        nc._ls[i] = Long.MAX_VALUE;
        nc._xs[i] = Integer.MIN_VALUE;
      } else nc._ls[i] = v;
    }
    return nc;
  }

  // get id of nth (chunk-relative) stored element
  protected final int getId(int off){
    return _idsz == 2
      ?UDP.get2(_mem,off)&0xFFFF
      :UDP.get4(_mem,off);
  }
  // get offset of nth (chunk-relative) stored element
  private final int getOff(int n){return OFF + (_idsz+_elsz)*n;}
  // extract integer value from an (byte)offset
  protected final long getIValue(int off){
    switch(_elsz){
      case 1: return _mem[off+_idsz];
      case 2: return UDP.get2(_mem, off + _idsz);
      case 4: return UDP.get4(_mem, off + _idsz);
      case 8: return UDP.get8(_mem, off + _idsz);
      default: throw H2O.unimpl();
   } 
  }

  // find offset of the chunk-relative row id, or -1 if not stored (i.e. sparse zero)
  protected final int findOffset(int idx) {
    if(idx >= _len)throw new IndexOutOfBoundsException();
    final byte [] mem = _mem;
    int sparseLen = sparseLen();
    if(sparseLen == 0)return 0;
    final int off = _lastOff;
    int lastIdx = getId(off);
    // check the last accessed elem
    if( idx == lastIdx ) return off;
    if(idx > lastIdx){
      // check the next one
      final int nextOff = off + _idsz + _elsz;
      if(nextOff < mem.length){
        int nextId =  getId(nextOff);
        if(idx < nextId)return off;
        if(idx == nextId){
          _lastOff = nextOff;
          return nextOff;
        }
      }
    }
    // no match so far, do binary search
    int lo=0, hi = sparseLen;
    while( lo+1 != hi ) {
      int mid = (hi+lo)>>>1;
      if( idx < getId(getOff(mid))) hi = mid;
      else          lo = mid;
    }
    int y =  getOff(lo);
    _lastOff = y;
    return y;
  }

  @Override public AutoBuffer write(AutoBuffer bb) { return bb.putA1(_mem, _mem.length); }
  @Override public Chunk read(AutoBuffer bb) {
    _mem   = bb.bufClose();
    _start = -1;
    _len = UDP.get4(_mem,0);
    _idsz = _mem[4];
    _elsz = _mem[5];
    int x = _elsz;
    int log = 0;
    while(x > 1){
      x = x >>> 1;
      ++log;
    }
    _elsz_log = log;
    return this;
  }

  public int skipCnt(int rid){
    int off = _lastOff;
    int currentId = getId(off);
    if(rid != currentId) off = findOffset(rid);
    if(off < _mem.length - _idsz - _elsz)
      return getId(off + _idsz + _elsz) - rid;
    return 0;
  }
  public final class Value {
    protected int off = OFF;
    public int rowInChunk(){return getId(off);}
    public long asLong(){
      long v = getIValue(off);
      if(v == NAS[(_elsz >>> 1) - 1]) throw new IllegalArgumentException("at8 but value is missing");
      return v;
    }
    public double asDouble(){
      long v = getIValue(off);
      return (v == NAS[_elsz_log-1])?Double.NaN:v;
    }
    public boolean isNa(){
      long v = getIValue(off);
      return (v == NAS[_elsz_log-1]);
    }
  }

  public Iterator<Value> values(){
    final Value val = new Value();
    return new Iterator<Value>(){
      @Override public boolean hasNext(){return val.off != _mem.length - (_idsz + _elsz);}
      @Override public Value next(){
        val.off += (_idsz + _elsz);
        return val;
      }
      @Override public void remove(){throw new UnsupportedOperationException();}
    };
  }
}