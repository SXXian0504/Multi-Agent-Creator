use multi_agent_creator;

ALTER TABLE article
    ADD COLUMN image_review_results JSON NULL COMMENT 'Image review results' AFTER images,
    ADD COLUMN image_execution_traces JSON NULL COMMENT 'Image execution traces' AFTER image_review_results,
    ADD COLUMN outline_image_intents JSON NULL COMMENT 'User image intents before outline confirmation' AFTER image_execution_traces,
    ADD COLUMN pending_image_revisions JSON NULL COMMENT 'Pending or historical image revision candidates' AFTER outline_image_intents;
