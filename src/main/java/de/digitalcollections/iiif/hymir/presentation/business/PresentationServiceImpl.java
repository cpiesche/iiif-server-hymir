package de.digitalcollections.iiif.hymir.presentation.business;

import de.digitalcollections.iiif.hymir.model.exception.InvalidDataException;
import de.digitalcollections.iiif.hymir.model.exception.ResolvingException;
import de.digitalcollections.iiif.hymir.presentation.backend.api.PresentationRepository;
import de.digitalcollections.iiif.hymir.presentation.business.api.PresentationSecurityService;
import de.digitalcollections.iiif.hymir.presentation.business.api.PresentationService;
import de.digitalcollections.iiif.model.sharedcanvas.Collection;
import de.digitalcollections.iiif.model.sharedcanvas.Manifest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PresentationServiceImpl implements PresentationService {
  private final PresentationRepository presentationRepository;

  private final PresentationSecurityService presentationSecurityService;

  @Autowired
  public PresentationServiceImpl(PresentationRepository presentationRepository,
                                 @Autowired(required=false) PresentationSecurityService presentationSecurityService) {
    this.presentationRepository = presentationRepository;
    this.presentationSecurityService = presentationSecurityService;
  }

  @Override
  public Collection getCollection(String name) throws ResolvingException, InvalidDataException {
    return presentationRepository.getCollection(name);
  }

  @Override
  public Manifest getManifest(String identifier) throws ResolvingException, InvalidDataException {
    if (presentationSecurityService != null && !presentationSecurityService.isAccessAllowed(identifier)) {
      throw new ResolvingException(); // TODO maybe throw an explicitely access disallowed exception
    }
    return presentationRepository.getManifest(identifier);
  }

  @Override
  public Instant getManifestModificationDate(String identifier) throws ResolvingException {
    return presentationRepository.getManifestModificationDate(identifier);
  }

  @Override
  public Instant getCollectionModificationDate(String identifier) throws ResolvingException {
    return presentationRepository.getCollectionModificationDate(identifier);
  }
}
