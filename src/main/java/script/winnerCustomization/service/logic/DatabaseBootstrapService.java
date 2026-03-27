package script.winnerCustomization.service.logic;
 
/**
 * Bootstraps the target database: creates DB, schema, user, and tables if needed.
 */
public interface DatabaseBootstrapService {
 
    void bootstrap();
}