/*
 * $Id$
 *
 * Copyright (c) 2005 VeriSign, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package se.rfc.unbound.validator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.xbill.DNS.*;

import se.rfc.unbound.*;

/**
 * This is a collection of routines encompassing the logic of validating
 * different message types.
 * 
 * @author davidb
 * @version $Revision$
 */
public class ValUtils
{

  // These are response subtypes. They are necessary for determining the
  // validation strategy. They have no bearing on the iterative resolution
  // algorithm, so they are confined here.

  /** Not subtyped yet. */
  public static final int UNTYPED   = 0;

  /** Not a recognized subtype. */
  public static final int UNKNOWN   = 1;

  /** A postive, direct, response. */
  public static final int POSITIVE  = 2;

  /** A postive response, with a CNAME/DNAME chain. */
  public static final int CNAME     = 3;

  /** A NOERROR/NODATA response. */
  public static final int NODATA    = 4;

  /** A NXDOMAIN response. */
  public static final int NAMEERROR = 5;

  /** A response to a qtype=ANY query. */
  public static final int ANY       = 6;

  private Logger          log       = Logger.getLogger(this.getClass());
  private static Logger   st_log    = Logger.getLogger(ValUtils.class);

  /** A local copy of the verifier object. */
  private DnsSecVerifier  mVerifier;

  public ValUtils(DnsSecVerifier verifier)
  {
    mVerifier = verifier;
  }

  /**
   * Given a response, classify ANSWER responses into a subtype.
   * 
   * @param m The response to classify.
   * 
   * @return A subtype ranging from UNKNOWN to NAMEERROR.
   */
  public static int classifyResponse(SMessage m)
  {
    // Normal Name Error's are easy to detect -- but don't mistake a CNAME
    // chain ending in NXDOMAIN.
    if (m.getRcode() == Rcode.NXDOMAIN
        && m.getCount(Section.ANSWER) == 0)
    {
      return NAMEERROR;
    }

    // Next is NODATA
    // st_log.debug("classifyResponse: ancount = " +
    // m.getCount(Section.ANSWER));
    if (m.getCount(Section.ANSWER) == 0)
    {
      return NODATA;
    }

    // We distinguish between CNAME response and other positive/negative
    // responses because CNAME answers require extra processing.
    int qtype = m.getQuestion().getType();

    // We distinguish between ANY and CNAME or POSITIVE because ANY responses
    // are validated differently.
    if (qtype == Type.ANY)
    {
      return ANY;
    }

    SRRset[] rrsets = m.getSectionRRsets(Section.ANSWER);

    // Note that DNAMEs will be ignored here, unless qtype=DNAME. Unless
    // qtype=CNAME, this will yield a CNAME response.
    for (int i = 0; i < rrsets.length; i++)
    {
      if (rrsets[i].getType() == qtype) return POSITIVE;
      if (rrsets[i].getType() == Type.CNAME) return CNAME;
    }

    st_log.warn("Failed to classify response message:\n" + m);
    return UNKNOWN;
  }

  /**
   * Given a response, determine the name of the "signer". This is primarily
   * to determine if the response is, in fact, signed at all, and, if so, what
   * is the name of the most pertinent keyset.
   * 
   * @param m The response to analyze.
   * @param request The request that generated the response.
   * @return a signer name, if the response is signed (even partially), or
   *         null if the response isn't signed.
   */
  public Name findSigner(SMessage m, Request request)
  {
    int subtype = classifyResponse(m);
    Name qname = request.getQName();

    SRRset[] rrsets;

    switch (subtype)
    {
      case POSITIVE :
      case CNAME :
      case ANY :
        // Check to see if the ANSWER section RRset
        rrsets = m.getSectionRRsets(Section.ANSWER);
        for (int i = 0; i < rrsets.length; i++)
        {
          if (rrsets[i].getName().equals(qname))
          {
            return rrsets[i].getSignerName();
          }
        }
        return null;

      case NAMEERROR :
      case NODATA :
        // Check to see if the AUTH section NSEC record(s) have rrsigs
        rrsets = m.getSectionRRsets(Section.AUTHORITY);
        for (int i = 0; i < rrsets.length; i++)
        {
          if (rrsets[i].getType() == Type.NSEC
              || rrsets[i].getType() == Type.NSEC3)
          {
            return rrsets[i].getSignerName();
          }
        }
        return null;
      default :
        log.debug("findSigner: could not find signer name "
            + "for unknown type response.");
        return null;
    }
  }

  public boolean dssetIsUsable(SRRset ds_rrset)
  {
    for (Iterator i = ds_rrset.rrs(); i.hasNext();)
    {
      DSRecord ds = (DSRecord) i.next();
      if (supportsDigestID(ds.getDigestID())
          && mVerifier.supportsAlgorithm(ds.getAlgorithm()))
      {
        return true;
      }
    }

    return false;
  }

  /**
   * Given a DS rrset and a DNSKEY rrset, match the DS to a DNSKEY and verify
   * the DNSKEY rrset with that key.
   * 
   * @param dnskey_rrset The DNSKEY rrset to match against. The security
   *          status of this rrset will be updated on a successful
   *          verification.
   * @param ds_rrset The DS rrset to match with. This rrset must already be
   *          trusted.
   * 
   * @return a KeyEntry. This will either contain the now trusted
   *         dnskey_rrset, a "null" key entry indicating that this DS
   *         rrset/DNSKEY pair indicate an secure end to the island of trust
   *         (i.e., unknown algorithms), or a "bad" KeyEntry if the dnskey
   *         rrset fails to verify. Note that the "null" response should
   *         generally only occur in a private algorithm scenario: normally
   *         this sort of thing is checked before fetching the matching DNSKEY
   *         rrset.
   */
  public KeyEntry verifyNewDNSKEYs(SRRset dnskey_rrset, SRRset ds_rrset)
  {
    if (!dnskey_rrset.getName().equals(ds_rrset.getName()))
    {
      log.debug("DNSKEY RRset did not match DS RRset by name!");
      return KeyEntry
          .newBadKeyEntry(ds_rrset.getName(), ds_rrset.getDClass());
    }

    // as long as this is false, we can consider this DS rrset to be
    // equivalent to no DS rrset.
    boolean hasUsefulDS = false;

    for (Iterator i = ds_rrset.rrs(); i.hasNext();)
    {
      DSRecord ds = (DSRecord) i.next();

      // Check to see if we can understand this DS.
      if (!supportsDigestID(ds.getDigestID())
          || !mVerifier.supportsAlgorithm(ds.getAlgorithm()))
      {
        continue;
      }

      // Once we see a single DS with a known digestID and algorithm, we
      // cannot return INSECURE (with a "null" KeyEntry).
      hasUsefulDS = true;

      DNSKEY : for (Iterator j = dnskey_rrset.rrs(); j.hasNext();)
      {
        DNSKEYRecord dnskey = (DNSKEYRecord) j.next();

        // Skip DNSKEYs that don't match the basic criteria.
        if (ds.getFootprint() != dnskey.getFootprint()
            || ds.getAlgorithm() != dnskey.getAlgorithm())
        {
          continue;
        }

        // Convert the candidate DNSKEY into a hash using the same DS hash
        // algorithm.
        byte[] key_hash = calculateDSHash(dnskey, ds.getDigestID());
        byte[] ds_hash = ds.getDigest();

        // see if there is a length mismatch (unlikely)
        if (key_hash.length != ds_hash.length)
        {
          continue DNSKEY;
        }

        for (int k = 0; k < key_hash.length; k++)
        {
          if (key_hash[k] != ds_hash[k]) continue DNSKEY;
        }

        // Otherwise, we have a match! Make sure that the DNSKEY verifies
        // *with this key*.
        byte res = mVerifier.verify(dnskey_rrset, dnskey);
        if (res == SecurityStatus.SECURE)
        {
          log.trace("DS matched DNSKEY.");
          dnskey_rrset.setSecurityStatus(SecurityStatus.SECURE);
          return KeyEntry.newKeyEntry(dnskey_rrset);
        }
        // If it didn't validate with the DNSKEY, try the next one!
      }
    }

    // None of the DS's worked out.

    // If no DSs were understandable, then this is OK.
    if (!hasUsefulDS)
    {
      log.debug("No usuable DS records were found -- treating as insecure.");
      return KeyEntry.newNullKeyEntry(ds_rrset.getName(), ds_rrset
          .getDClass(), ds_rrset.getTTL());
    }
    // If any were understandable, then it is bad.
    log.debug("Failed to match any usable DS to a DNSKEY.");
    return KeyEntry.newBadKeyEntry(ds_rrset.getName(), ds_rrset.getDClass());
  }

  /**
   * Given a DNSKEY record, generate the DS record from it.
   * 
   * @param keyrec the DNSKEY record in question.
   * @param ds_alg The DS digest algorithm in use.
   * @return the corresponding {@link org.xbill.DNS.DSRecord}
   */
  public static byte[] calculateDSHash(DNSKEYRecord keyrec, int ds_alg)
  {
    DNSOutput os = new DNSOutput();

    os.writeByteArray(keyrec.getName().toWireCanonical());
    os.writeByteArray(keyrec.rdataToWireCanonical());

    try
    {
      MessageDigest md = null;
      switch (ds_alg)
      {
        case DSRecord.SHA1_DIGEST_ID :
          md = MessageDigest.getInstance("SHA");
          return md.digest(os.toByteArray());
        case DSRecord.SHA256_DIGEST_ID:
          SHA256 sha = new SHA256();
          sha.setData(os.toByteArray());
          return sha.getDigest();
        default :
          st_log.warn("Unknown DS algorithm: " + ds_alg);
          return null;
      }

    }
    catch (NoSuchAlgorithmException e)
    {
      st_log.error("Error using DS algorithm: " + ds_alg, e);
      return null;
    }
  }

  public static boolean supportsDigestID(int digest_id)
  {
    if (digest_id == DSRecord.SHA1_DIGEST_ID) return true;
    if (digest_id == DSRecord.SHA256_DIGEST_ID) return true;
    return false;
  }

  /**
   * Check to see if a type is a special DNSSEC type.
   * 
   * @param type The type.
   * 
   * @return true if the type is one of the special DNSSEC types.
   */
  public static boolean isDNSSECType(int type)
  {
    switch (type)
    {
      case Type.DNSKEY :
      case Type.NSEC :
      case Type.DS :
      case Type.RRSIG :
      case Type.NSEC3 :
        return true;
      default :
        return false;
    }
  }

  /**
   * Set the security status of a particular RRset. This will only upgrade the
   * security status.
   * 
   * @param rrset The SRRset to update.
   * @param security The security status.
   */
  public static void setRRsetSecurity(SRRset rrset, int security)
  {
    if (rrset == null) return;

    int cur_sec = rrset.getSecurityStatus();
    if (cur_sec == SecurityStatus.UNCHECKED || security > cur_sec)
    {
      rrset.setSecurityStatus(security);
    }
  }

  /**
   * Set the security status of a message and all of its RRsets. This will
   * only upgrade the status of the message (i.e., set to more secure, not
   * less) and all of the RRsets.
   * 
   * @param m
   * @param security KeyEntry ke;
   * 
   * SMessage m = response.getSMessage(); SRRset ans_rrset =
   * m.findAnswerRRset(qname, qtype, qclass);
   * 
   * ke = verifySRRset(ans_rrset, key_rrset); if
   * (ans_rrset.getSecurityStatus() != SecurityStatus.SECURE) { return; }
   * key_rrset = ke.getRRset();
   */
  public static void setMessageSecurity(SMessage m, int security)
  {
    if (m == null) return;

    int cur_sec = m.getStatus();
    if (cur_sec == SecurityStatus.UNCHECKED || security > cur_sec)
    {
      m.setStatus(security);
    }

    for (int section = Section.ANSWER; section <= Section.ADDITIONAL; section++)
    {
      SRRset[] rrsets = m.getSectionRRsets(section);
      for (int i = 0; i < rrsets.length; i++)
      {
        setRRsetSecurity(rrsets[i], security);
      }
    }
  }

  /**
   * Given an SRRset that is signed by a DNSKEY found in the key_rrset, verify
   * it. This will return the status (either BOGUS or SECURE) and set that
   * status in rrset.
   * 
   * @param rrset The SRRset to verify.
   * @param key_rrset The set of keys to verify against.
   * @return The status (BOGUS or SECURE).
   */
  public byte verifySRRset(SRRset rrset, SRRset key_rrset)
  {
    String rrset_name = rrset.getName() + "/" + Type.string(rrset.getType())
        + "/" + DClass.string(rrset.getDClass());

    if (rrset.getSecurityStatus() == SecurityStatus.SECURE)
    {
      log.trace("verifySRRset: rrset <" + rrset_name
          + "> previously found to be SECURE");
      return SecurityStatus.SECURE;
    }

    byte status = mVerifier.verify(rrset, key_rrset);
    if (status != SecurityStatus.SECURE)
    {
      log.debug("verifySRRset: rrset <" + rrset_name + "> found to be BAD");
      status = SecurityStatus.BOGUS;
    }
    else
    {
      log.trace("verifySRRset: rrset <" + rrset_name + "> found to be SECURE");
    }

    rrset.setSecurityStatus(status);
    return status;
  }

  /**
   * Determine if a given type map has a given typ.
   * 
   * @param types The type map from the NSEC record.
   * @param type The type to look for.
   * @return true if the type is present in the type map, false otherwise.
   */
  public static boolean typeMapHasType(int[] types, int type)
  {
    for (int i = 0; i < types.length; i++)
    {
      if (types[i] == type) return true;
    }
    return false;
  }

  /**
   * Determine by looking at a signed RRset whether or not the rrset name was
   * the result of a wildcard expansion.
   * 
   * @param rrset The rrset to examine.
   * @return true if the rrset is a wildcard expansion. This will return false
   *         for all unsigned rrsets.
   */
  public static boolean rrsetIsWildcardExpansion(RRset rrset)
  {
    if (rrset == null) return false;
    RRSIGRecord rrsig = (RRSIGRecord) rrset.firstSig();

    if (rrset.getName().labels() - 1 > rrsig.getLabels())
    {
      return true;
    }

    return false;
  }

  /**
   * Determine by looking at a signed RRset whether or not the RRset name was
   * the result of a wildcard expansion. If so, return the name of the
   * generating wildcard.
   * 
   * @param rrset The rrset to chedck.
   * @return the wildcard name, if the rrset was synthesized from a wildcard.
   *         null if not.
   */
  public static Name rrsetWildcard(RRset rrset)
  {
    if (rrset == null) return null;
    RRSIGRecord rrsig = (RRSIGRecord) rrset.firstSig();

    // if the RRSIG label count is shorter than the number of actual labels,
    // then this rrset was synthesized from a wildcard.
    // Note that the RRSIG label count doesn't count the root label.
    int label_diff = (rrset.getName().labels() - 1) - rrsig.getLabels();
    if (label_diff > 0)
    {
      return rrset.getName().wild(label_diff);
    }
    return null;
  }

  public static Name closestEncloser(Name domain, NSECRecord nsec)
  {
    Name n1 = domain.longestCommonName(nsec.getName());
    Name n2 = domain.longestCommonName(nsec.getNext());

    return (n1.labels() > n2.labels()) ? n1 : n2;
  }

  public static Name nsecWildcard(Name domain, NSECRecord nsec)
  {
    try
    {
      return new Name("*", closestEncloser(domain, nsec));
    }
    catch (TextParseException e)
    {
      // this should never happen.
      return null;
    }
  }

  /**
   * Determine if the given NSEC proves a NameError (NXDOMAIN) for a given
   * qname.
   * 
   * @param nsec The NSEC to check.
   * @param qname The qname to check against.
   * @param signerName The signer name of the NSEC record, which is used as
   *          the zone name, for a more precise (but perhaps more brittle)
   *          check for the last NSEC in a zone.
   * @return true if the NSEC proves the condition.
   */
  public static boolean nsecProvesNameError(NSECRecord nsec, Name qname,
      Name signerName)
  {
    Name owner = nsec.getName();
    Name next = nsec.getNext();

    // If NSEC owner == qname, then this NSEC proves that qname exists.
    if (qname.equals(owner))
    {
      return false;
    }

    // If NSEC is a parent of qname, we need to check the type map
    // If the parent name has a DNAME or is a delegation point, then this NSEC
    // is being misused.
    if (qname.subdomain(owner)
        && (typeMapHasType(nsec.getTypes(), Type.DNAME) || (typeMapHasType(nsec
            .getTypes(),
            Type.NS) && !typeMapHasType(nsec.getTypes(), Type.SOA))))
    {
      return false;
    }

    if (qname.compareTo(owner) > 0 && (qname.compareTo(next) < 0)
        || signerName.equals(next))
    {
      return true;
    }
    return false;
  }

  /**
   * Determine if a NSEC record proves the non-existence of a wildcard that
   * could have produced qname.
   * 
   * @param nsec The nsec to check.
   * @param qname The qname to check against.
   * @param signerName The signer name for the NSEC rrset, used as the zone
   *          name.
   * @return true if the NSEC proves the condition.
   */
  public static boolean nsecProvesNoWC(NSECRecord nsec, Name qname,
      Name signerName)
  {
    Name owner = nsec.getName();
    Name next = nsec.getNext();

    int qname_labels = qname.labels();
    int signer_labels = signerName.labels();

    for (int i = qname_labels - signer_labels; i > 0; i--)
    {
      Name wc_name = qname.wild(i);
      if (wc_name.compareTo(owner) > 0
          && (wc_name.compareTo(next) < 0 || signerName.equals(next)))
      {
        return true;
      }
    }

    return false;
  }

  /**
   * Determine if a NSEC proves the NOERROR/NODATA conditions. This will also
   * handle the empty non-terminal (ENT) case and partially handle the
   * wildcard case. If the ownername of 'nsec' is a wildcard, the validator
   * must still be provided proof that qname did not directly exist and that
   * the wildcard is, in fact, *.closest_encloser.
   * 
   * @param nsec The NSEC to check
   * @param qname The query name to check against.
   * @param qtype The query type to check against.
   * @return true if the NSEC proves the condition.
   */
  public static boolean nsecProvesNodata(NSECRecord nsec, Name qname,
      int qtype)
  {
    if (!nsec.getName().equals(qname))
    {
      // wildcard checking.
      
      // If this is a wildcard NSEC, make sure that a) it was possible to have
      // generated qname from the wildcard and b) the type map does not
      // contain qtype. Note that this does NOT prove that this wildcard was
      // the applicable wildcard.
      if (nsec.getName().isWild())
      {
        // the is the purported closest encloser.
        Name ce = new Name(nsec.getName(), 1);
        
        // The qname must be a strict subdomain of the closest encloser, and
        // the qtype must be absent from the type map.
        if (!qname.strictSubdomain(ce) || typeMapHasType(nsec.getTypes(), qtype))
        {
          return false;
        }
        return true;
      }
      
      // empty-non-terminal checking.
      
      // If the nsec is proving that qname is an ENT, the nsec owner will be
      // less than qname, and the next name will be a child domain of the
      // qname.
      if (nsec.getNext().strictSubdomain(qname)
          && qname.compareTo(nsec.getName()) > 0)
      {
        return true;
      }
      // Otherwise, this NSEC does not prove ENT, so it does not prove NODATA.
      return false;
    }

    // If the qtype exists, then we should have gotten it.
    if (typeMapHasType(nsec.getTypes(), qtype))
    {
      return false;
    }

    // if the name is a CNAME node, then we should have gotten the CNAME
    if (typeMapHasType(nsec.getTypes(), Type.CNAME))
    {
      return false;
    }
    
    // If an NS set exists at this name, and NOT a SOA (so this is a zone cut,
    // not a zone apex), then we should have gotten a referral (or we just got
    // the wrong NSEC).
    if (typeMapHasType(nsec.getTypes(), Type.NS)
        && !typeMapHasType(nsec.getTypes(), Type.SOA))
    {
      return false;
    }

    return true;
  }

  public static int nsecProvesNoDS(NSECRecord nsec, Name qname)
  {
    // Could check to make sure the qname is a subdomain of nsec
    int[] types = nsec.getTypes();
    if (typeMapHasType(types, Type.SOA) || typeMapHasType(types, Type.DS))
    {
      // SOA present means that this is the NSEC from the child, not the
      // parent (so it is the wrong one)
      // DS present means that there should have been a positive response to
      // the DS query, so there is something wrong.
      return SecurityStatus.BOGUS;
    }

    if (!typeMapHasType(types, Type.NS))
    {
      // If there is no NS at this point at all, then this doesn't prove
      // anything one way or the other.
      return SecurityStatus.INSECURE;
    }
    // Otherwise, this proves no DS.
    return SecurityStatus.SECURE;
  }

}
